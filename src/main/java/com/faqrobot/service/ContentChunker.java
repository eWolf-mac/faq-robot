package com.faqrobot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容分片器 — 自动检测内容结构并选择最优分片策略
 *
 * <p>策略优先级（从最明确的结构信号到最弱）：
 * <ol>
 *   <li>Markdown 标题模式 — 检测到 # 标题，按标题切分</li>
 *   <li>重复字段模式 — 每行都是 {@code key: value} 格式且 key 呈周期性重复，
 *       按周期分片，首个字段的值作为标题</li>
 *   <li>二行组模式 — 非空行成对出现（常见于 Q&A 文档），按每 2 行分片</li>
 *   <li>逐行模式 — 大多数非空行孤立出现，按行分片</li>
 *   <li>段落模式（回退）— 按空行分段，控制每片大小</li>
 * </ol>
 */
@Slf4j
@Component
public class ContentChunker {

    private static final int MAX_CHUNK_SIZE = 800;
    private static final int MIN_CHUNK_SIZE = 100;

    private static final Pattern MD_HEADER = Pattern.compile("^#{1,4}\\s+");

    // 匹配 "key": value 或 key: value 或 key：value，提取 key 名称
    private static final Pattern KV_LINE = Pattern.compile(
            "^\"([^\"]+)\"\\s*:\\s*|^([^：:\\s]+)\\s*[：:]\\s*");

    /**
     * 将文本分片为多个片段，自动选择最优策略
     */
    public List<Chunk> chunk(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String[] lines = text.split("\\r?\\n");

        // 1. Markdown 标题模式（# 是明确的结构信号）
        if (detectMarkdownHeaders(lines)) {
            log.info("检测到 Markdown 标题，按标题分片");
            return chunkByHeaders(lines);
        }

        // 2. 重复字段模式（key: value 行周周期性重复）
        int fieldCycle = detectFieldCycle(lines);
        if (fieldCycle > 0) {
            log.info("检测到重复字段模式，周期={}，按字段组分片", fieldCycle);
            return chunkByFieldPattern(lines, fieldCycle);
        }

        // 3. 分析非空行的连续段结构
        int[] runHistogram = buildRunHistogram(lines);
        int totalGrouped = runHistogram[0] + runHistogram[1] + runHistogram[2];
        if (totalGrouped == 0) return Collections.emptyList();

        // 4. 二行组为主 → Q&A / 成对结构
        double pairRatio = (double) runHistogram[1] / totalGrouped;
        if (pairRatio >= 0.4) {
            log.info("检测到二行组结构 (占比 {:.0%})，按每 2 行分片", pairRatio);
            return chunkByLineGroups(lines, 2);
        }

        // 5. 孤行为主 + 弱段落结构 → 逐行模式
        double singleRatio = (double) runHistogram[0] / totalGrouped;
        double emptyRatio = calcEmptyRatio(lines);
        if (singleRatio >= 0.5 && emptyRatio < 0.15) {
            log.info("检测到逐行结构，按行分片");
            return chunkByLineGroups(lines, 1);
        }

        // 6. 回退到段落模式
        log.info("使用段落模式分片 (二行组 {:.0%}, 孤行 {:.0%}, 空行 {:.0%})",
                pairRatio, singleRatio, emptyRatio);
        return chunkByParagraphs(text);
    }

    // ==================== 结构检测 ====================

    private boolean detectMarkdownHeaders(String[] lines) {
        int headerCount = 0;
        for (String line : lines) {
            if (MD_HEADER.matcher(line).find()) {
                headerCount++;
            }
        }
        return headerCount >= 2;
    }

    // ==================== 重复字段模式检测 ====================

    /**
     * 检测文件中是否存在重复的 key: value 字段模式。
     * 分析所有非空行，提取字段名（key），寻找最短的重复周期。
     *
     * @return 检测到的周期（2~8），未检测到返回 -1
     */
    private int detectFieldCycle(String[] lines) {
        List<String> fields = new ArrayList<>();
        int nonEmptyCount = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                // 有太多空行说明不是紧凑的字段结构
                continue;
            }
            nonEmptyCount++;
            String name = extractFieldName(trimmed);
            if (name == null) return -1;
            fields.add(name);
        }

        // 至少 6 行非空，且 70% 以上的行能识别为 KV 格式
        if (fields.size() < 6) return -1;
        double kvRatio = (double) fields.size() / nonEmptyCount;
        if (kvRatio < 0.7) return -1;

        // 尝试周期 2~8，找最短的一致周期
        for (int period = 2; period <= 8 && period <= fields.size() / 2; period++) {
            if (isConsistentCycle(fields, period)) {
                return period;
            }
        }
        return -1;
    }

    /**
     * 从一行中提取字段名（key 部分）。
     * 支持格式: "key": value, key: value, key：value
     */
    private String extractFieldName(String line) {
        Matcher m = KV_LINE.matcher(line);
        if (m.find()) {
            // group(1): 引号内的 key ("key":), group(2): 无引号的 key (key:)
            String key = m.group(1) != null ? m.group(1) : m.group(2);
            return key.toLowerCase();
        }
        return null;
    }

    /**
     * 字段名列表是否在给定周期下保持一致性。
     * 一致性要求：fields[i] == fields[i+period] 的比例 >= 95%
     */
    private boolean isConsistentCycle(List<String> fields, int period) {
        int matches = 0;
        int total = 0;
        for (int i = 0; i + period < fields.size(); i++) {
            total++;
            if (fields.get(i).equals(fields.get(i + period))) {
                matches++;
            }
        }
        return total >= 4 && (double) matches / total >= 0.95;
    }

    // ==================== 连续段结构检测 ====================

    private int[] buildRunHistogram(String[] lines) {
        int[] hist = new int[3];
        int run = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                if (run > 0) {
                    addToHistogram(hist, run);
                    run = 0;
                }
            } else {
                run++;
            }
        }
        if (run > 0) {
            addToHistogram(hist, run);
        }
        return hist;
    }

    private void addToHistogram(int[] hist, int run) {
        if (run == 1) hist[0]++;
        else if (run == 2) hist[1]++;
        else hist[2]++;
    }

    private double calcEmptyRatio(String[] lines) {
        int empty = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) empty++;
        }
        return lines.length == 0 ? 0 : (double) empty / lines.length;
    }

    // ==================== 分片策略 ====================

    /**
     * 按重复字段模式分片 — 每 period 行组成一个分片，首字段值作标题
     */
    private List<Chunk> chunkByFieldPattern(String[] lines, int period) {
        List<Chunk> result = new ArrayList<>();
        List<String> recordLines = new ArrayList<>();
        int lineInRecord = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!recordLines.isEmpty()) {
                    result.add(buildFieldChunk(recordLines));
                    recordLines.clear();
                    lineInRecord = 0;
                }
                continue;
            }
            recordLines.add(trimmed);
            lineInRecord++;

            if (lineInRecord == period) {
                result.add(buildFieldChunk(recordLines));
                recordLines.clear();
                lineInRecord = 0;
            }
        }
        if (!recordLines.isEmpty()) {
            result.add(buildFieldChunk(recordLines));
        }
        // 不对字段模式做小片段合并 — 每条记录语义独立
        return result;
    }

    /**
     * 从多条字段行构建一个分片：首行值作标题，完整内容保留
     */
    private Chunk buildFieldChunk(List<String> lines) {
        String title = extractFieldValue(lines.get(0));
        String content = String.join("\n", lines);
        return new Chunk(title, content);
    }

    /**
     * 提取字段行中 key: 后面的 value 部分，去除首尾引号
     */
    private String extractFieldValue(String line) {
        String value = line.replaceFirst(
                "^\"[^\"]+\"\\s*:\\s*|^[^：:\\s]+\\s*[：:]\\s*", "").trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 按固定行数分组（1 行 = 逐行，2 行 = Q&A 对）
     */
    private List<Chunk> chunkByLineGroups(String[] lines, int groupSize) {
        List<Chunk> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int lineInGroup = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushGroupIfFull(result, buf, lineInGroup, groupSize);
                buf = new StringBuilder();
                lineInGroup = 0;
                continue;
            }

            if (lineInGroup > 0) buf.append("\n");
            buf.append(trimmed);
            lineInGroup++;

            if (lineInGroup == groupSize) {
                if (buf.length() > MAX_CHUNK_SIZE) {
                    for (String part : splitBySentence(buf.toString())) {
                        result.add(buildChunk(part, result.size()));
                    }
                } else {
                    result.add(buildChunk(buf.toString(), result.size()));
                }
                buf = new StringBuilder();
                lineInGroup = 0;
            }
        }
        if (buf.length() > 0) {
            result.add(buildChunk(buf.toString(), result.size()));
        }

        return mergeSmallResultChunks(result);
    }

    private void flushGroupIfFull(List<Chunk> result, StringBuilder buf,
                                  int lineInGroup, int groupSize) {
        if (buf.length() > 0 && lineInGroup == groupSize) {
            result.add(buildChunk(buf.toString(), result.size()));
        }
    }

    /**
     * 按 Markdown 标题切分
     */
    private List<Chunk> chunkByHeaders(String[] lines) {
        List<Chunk> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String currentTitle = null;

        for (String line : lines) {
            if (MD_HEADER.matcher(line).find()) {
                if (buf.length() > 0) {
                    String title = currentTitle != null ? currentTitle
                            : "片段 " + (result.size() + 1);
                    result.addAll(contentToChunks(buf.toString().trim(), title));
                    buf = new StringBuilder();
                }
                currentTitle = line.replaceFirst("^#+\\s*", "").trim();
                continue;
            }
            if (buf.length() > 0) buf.append("\n");
            buf.append(line.trim().isEmpty() ? "" : line);
        }

        if (buf.length() > 0) {
            String title = currentTitle != null ? currentTitle
                    : "片段 " + (result.size() + 1);
            result.addAll(contentToChunks(buf.toString().trim(), title));
        }

        return mergeSmallResultChunks(result);
    }

    /**
     * 段落模式 — 按空行分段后控制大小
     */
    private List<Chunk> chunkByParagraphs(String text) {
        String[] paragraphs = text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n");
        List<String> rawChunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            if (current.length() + trimmed.length() > MAX_CHUNK_SIZE
                    && current.length() > MIN_CHUNK_SIZE) {
                rawChunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(trimmed);
        }
        if (current.length() > 0) {
            rawChunks.add(current.toString().trim());
        }

        List<String> merged = mergeSmallStringChunks(rawChunks);

        List<String> finalChunks = new ArrayList<>();
        for (String chunk : merged) {
            if (chunk.length() > MAX_CHUNK_SIZE) {
                finalChunks.addAll(splitBySentence(chunk));
            } else {
                finalChunks.add(chunk);
            }
        }

        List<Chunk> result = new ArrayList<>();
        for (int i = 0; i < finalChunks.size(); i++) {
            result.add(buildChunk(finalChunks.get(i), i));
        }
        return result;
    }

    // ==================== 辅助方法 ====================

    private List<Chunk> contentToChunks(String content, String baseTitle) {
        if (content.length() <= MAX_CHUNK_SIZE) {
            return Collections.singletonList(new Chunk(baseTitle, content));
        }
        List<String> parts = splitBySentence(content);
        List<Chunk> result = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String title = parts.size() == 1 ? baseTitle
                    : baseTitle + " (" + (i + 1) + ")";
            result.add(new Chunk(title, parts.get(i)));
        }
        return result;
    }

    private List<String> mergeSmallStringChunks(List<String> chunks) {
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String chunk : chunks) {
            if (buf.length() + chunk.length() > MAX_CHUNK_SIZE
                    && buf.length() > MIN_CHUNK_SIZE) {
                merged.add(buf.toString().trim());
                buf = new StringBuilder();
            }
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(chunk);
        }
        if (buf.length() > 0) {
            merged.add(buf.toString().trim());
        }
        return merged;
    }

    private List<Chunk> mergeSmallResultChunks(List<Chunk> chunks) {
        if (chunks.size() <= 1) return chunks;
        List<Chunk> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String firstTitle = null;

        for (Chunk chunk : chunks) {
            if (buf.length() + chunk.content.length() > MAX_CHUNK_SIZE
                    && buf.length() > MIN_CHUNK_SIZE) {
                merged.add(buildChunk(buf.toString(), firstTitle, merged.size()));
                buf = new StringBuilder();
                firstTitle = null;
            }
            if (firstTitle == null) firstTitle = chunk.title;
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(chunk.content);
        }
        if (buf.length() > 0) {
            merged.add(buildChunk(buf.toString(), firstTitle, merged.size()));
        }
        return merged;
    }

    private List<String> splitBySentence(String text) {
        List<String> result = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？；\\n])");
        StringBuilder buf = new StringBuilder();
        for (String sentence : sentences) {
            if (buf.length() + sentence.length() > MAX_CHUNK_SIZE
                    && buf.length() > MIN_CHUNK_SIZE) {
                result.add(buf.toString().trim());
                buf = new StringBuilder();
            }
            buf.append(sentence);
        }
        if (buf.length() > 0) {
            result.add(buf.toString().trim());
        }
        return result.isEmpty() ? Collections.singletonList(text) : result;
    }

    private Chunk buildChunk(String content, int index) {
        return buildChunk(content, null, index);
    }

    private Chunk buildChunk(String content, String title, int index) {
        String t = title != null ? title : generateChunkTitle(content, index);
        return new Chunk(t, content);
    }

    private String generateChunkTitle(String content, int index) {
        if (content == null || content.isEmpty()) {
            return "片段 " + (index + 1);
        }
        String firstLine = content.split("\\n|\\r\\n")[0].trim();
        if (firstLine.length() > 3 && firstLine.length() <= 60) {
            return firstLine;
        }
        return content.substring(0, Math.min(30, content.length())).trim() + "...";
    }

    // ==================== 分片结果 ====================

    public static class Chunk {
        private final String title;
        private final String content;

        public Chunk(String title, String content) {
            this.title = title;
            this.content = content;
        }

        public String getTitle() { return title; }
        public String getContent() { return content; }
    }
}
