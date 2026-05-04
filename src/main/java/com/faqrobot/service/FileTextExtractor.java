package com.faqrobot.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.poifs.filesystem.NotOLE2FileException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 多格式文件文本提取器，支持 txt/md/docx/xlsx/xls
 * <p>
 * 编码策略：纯文本文件优先 UTF-8，若字节序列不合法则自动回退 GBK/GB2312（中文 Windows 默认编码）。
 * Excel 加密文件会给出明确提示，避免将加密二进制当文本读出乱码。
 */
@Slf4j
@Component
public class FileTextExtractor {

    /** GBK / GB2312 兼容编码集（中文 Windows ANSI 默认） */
    private static final Charset GBK = Charset.forName("GBK");

    /**
     * 根据文件名后缀提取文本内容
     */
    public String extract(InputStream inputStream, String fileName) throws IOException {
        if (fileName == null) {
            throw new IOException("文件名不能为空");
        }
        String lower = fileName.toLowerCase();

        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".csv")) {
            return extractPlainText(inputStream);
        }
        if (lower.endsWith(".docx")) {
            return extractDocx(inputStream);
        }
        if (lower.endsWith(".xlsx")) {
            return extractExcel(inputStream, "xlsx");
        }
        if (lower.endsWith(".xls")) {
            return extractExcel(inputStream, "xls");
        }

        throw new IOException("不支持的文件格式: " + fileName + " (支持 txt/md/docx/xlsx/xls)");
    }

    /**
     * 纯文本文件 — 自动检测编码（UTF-8 优先，无效则回退 GBK）
     */
    private String extractPlainText(InputStream inputStream) throws IOException {
        byte[] bytes = org.apache.commons.io.IOUtils.toByteArray(inputStream);
        return decodeText(bytes, "txt");
    }

    /**
     * Word .docx (Office Open XML)
     */
    private String extractDocx(InputStream inputStream) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            if (text == null || text.trim().isEmpty()) {
                throw new IOException("Word 文档内容为空");
            }
            log.info("extracted docx: {} chars", text.length());
            return text;
        }
    }

    /**
     * Excel 通用提取 — 自动识别 .xls (HSSF/OLE2) 和 .xlsx (XSSF/OOXML) 格式
     * <p>
     * 先按文件扩展名尝试对应解析器，失败时自动切换另一种格式；
     * 加密文件（含 OLE2 和 OOXML 两种加密异常）给出明确提示；
     * 两种 Excel 解析器均失败时，兜底尝试按纯文本读取（自动检测编码）。
     */
    private String extractExcel(InputStream inputStream, String expectedFormat) throws IOException {
        byte[] data = org.apache.commons.io.IOUtils.toByteArray(inputStream);
        log.info("Excel 文件读取完成: {} bytes, 预期格式={}", data.length, expectedFormat);

        String firstError = null;
        String secondError = null;

        if ("xls".equals(expectedFormat)) {
            // .xls → 优先 HSSFWorkbook（OLE2），失败再尝试 XSSFWorkbook（OOXML）
            try {
                return extractFromWorkbook(new HSSFWorkbook(new ByteArrayInputStream(data)), "xls");
            } catch (NotOLE2FileException e) {
                firstError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("HSSFWorkbook 解析失败 → 尝试 XSSFWorkbook: {}", firstError);
                secondError = tryXssf(data);
            } catch (EncryptedDocumentException e) {
                throw new IOException("该 .xls 文件已加密（设有密码保护），请先解密后再导入", e);
            } catch (Exception e) {
                firstError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("HSSFWorkbook 解析异常 → 尝试 XSSFWorkbook: {}", firstError);
                secondError = tryXssf(data);
            }
        } else {
            // .xlsx → 优先 XSSFWorkbook（OOXML），失败再尝试 HSSFWorkbook（OLE2）
            try {
                return extractFromWorkbook(new XSSFWorkbook(new ByteArrayInputStream(data)), "xlsx");
            } catch (NotOfficeXmlFileException e) {
                firstError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("XSSFWorkbook 解析失败（非 OOXML）→ 尝试 HSSFWorkbook: {}", firstError);
                secondError = tryHssf(data);
            } catch (EncryptedDocumentException e) {
                throw new IOException("该 .xlsx 文件已加密（设有密码保护），请先解密后再导入", e);
            } catch (Exception e) {
                firstError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("XSSFWorkbook 解析异常 → 尝试 HSSFWorkbook: {}", firstError);
                secondError = tryHssf(data);
            }
        }

        // 两种 Excel 解析器均失败，最后兜底：尝试按纯文本读取（自动检测编码）
        log.warn("Excel 解析均失败 — 第一轮: [{}] | 第二轮: [{}]，兜底按纯文本读取", firstError, secondError);
        try {
            String plainText = decodeText(data, expectedFormat);
            if (plainText.trim().isEmpty()) {
                throw new IOException("内容为空");
            }
            log.info("Excel 文件按纯文本兜底读取成功（{} 编码），共 {} 字符，前100字: {}",
                    isUtf8(data) ? "UTF-8" : "GBK", plainText.length(),
                    plainText.substring(0, Math.min(100, plainText.length())));
            return plainText;
        } catch (Exception ex) {
            throw new IOException(String.format(
                    "无法解析该文件（扩展名 %s）— 第一轮: [%s] | 第二轮: [%s] | 纯文本兜底也失败。"
                    + "请确认文件未损坏、未加密且格式正确",
                    expectedFormat, firstError, secondError), ex);
        }
    }

    /** 尝试 XSSFWorkbook 解析，返回异常信息（成功则直接返回文本） */
    private String tryXssf(byte[] data) throws IOException {
        try {
            return extractFromWorkbook(new XSSFWorkbook(new ByteArrayInputStream(data)), "xlsx");
        } catch (EncryptedDocumentException e) {
            throw new IOException("该文件是加密的 Excel 文档（设有密码保护），请先解密后再导入", e);
        } catch (Exception e2) {
            return e2.getClass().getSimpleName() + ": " + e2.getMessage();
        }
    }

    /** 尝试 HSSFWorkbook 解析，返回异常信息（成功则直接返回文本） */
    private String tryHssf(byte[] data) throws IOException {
        try {
            return extractFromWorkbook(new HSSFWorkbook(new ByteArrayInputStream(data)), "xls");
        } catch (EncryptedDocumentException e) {
            throw new IOException("该文件是加密的 Excel 文档（设有密码保护），请先解密后再导入", e);
        } catch (Exception e2) {
            return e2.getClass().getSimpleName() + ": " + e2.getMessage();
        }
    }

    /**
     * 智能解码字节数组为文本：UTF-8 优先，无效则回退 GBK
     * <p>
     * 判断依据：将字节按 UTF-8 解码后重新编码，若与原始字节不一致则说明非 UTF-8，
     * 此时改用 GBK（兼容中文 Windows 下另存为 ANSI 的 txt/csv 文件）。
     */
    private String decodeText(byte[] data, String sourceLabel) {
        // 跳过 BOM 头（UTF-8 BOM: EF BB BF）
        int offset = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            offset = 3;
            log.debug("检测到 UTF-8 BOM，跳过 3 字节");
        }

        byte[] payload = (offset > 0) ? java.util.Arrays.copyOfRange(data, offset, data.length) : data;

        if (isUtf8(payload)) {
            String text = new String(payload, StandardCharsets.UTF_8);
            log.info("文本解码: {} 使用 UTF-8, {} 字符", sourceLabel, text.length());
            return text;
        }

        // 非 UTF-8，回退 GBK（中文 Windows ANSI 默认编码）
        String text = new String(payload, GBK);
        log.info("文本解码: {} 使用 GBK（UTF-8 校验不通过）, {} 字符", sourceLabel, text.length());
        return text;
    }

    /**
     * 判断字节数组是否为合法的 UTF-8 编码
     */
    private boolean isUtf8(byte[] data) {
        try {
            String decoded = new String(data, StandardCharsets.UTF_8);
            byte[] reEncoded = decoded.getBytes(StandardCharsets.UTF_8);
            return java.util.Arrays.equals(data, reEncoded);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 遍历所有 Sheet 和行，提取单元格文本
     */
    private String extractFromWorkbook(Workbook workbook, String format) {
        StringBuilder sb = new StringBuilder();
        int sheetCount = workbook.getNumberOfSheets();
        for (int i = 0; i < sheetCount; i++) {
            Sheet sheet = workbook.getSheetAt(i);
            String sheetName = sheet.getSheetName();
            sb.append("【").append(sheetName).append("】\n");
            for (Row row : sheet) {
                StringBuilder rowText = new StringBuilder();
                for (Cell cell : row) {
                    String cellValue = getCellString(cell);
                    if (!cellValue.isEmpty()) {
                        if (rowText.length() > 0) rowText.append("\t");
                        rowText.append(cellValue);
                    }
                }
                if (rowText.length() > 0) {
                    sb.append(rowText).append("\n");
                }
            }
            sb.append("\n");
        }
        String text = sb.toString().trim();
        log.info("extracted {} ({} sheets): {} chars", format, sheetCount, text.length());
        // 诊断日志：输出前200字符，便于排查乱码问题
        if (text.length() > 0) {
            log.info("  提取文本前200字: {}", text.substring(0, Math.min(200, text.length())));
        }
        return text;
    }

    /**
     * 获取单元格的字符串值
     */
    private String getCellString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    return String.valueOf((long) val);
                }
                return String.valueOf(val);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }
}
