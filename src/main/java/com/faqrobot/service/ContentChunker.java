package com.faqrobot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内容分片器 — 自动检测内容结构并选择最优分片策略
 *
 * <p>策略优先级：
 * <ol>
 *   <li>Q&A 对模式 — 检测到交替的"问/答"行，按每 2 行分片</li>
 *   <li>Markdown 标题模式 — 检测到 # 标题，按标题切分</li>
 *   <li>逐行模式 — 每行长度适中且行间无明显段落结构，按行分片</li>
 *   <li>段落模式（回退）— 按空行分段，控制每片大小</li>
 * </ol>
 */
@Slf4j
@Component
public class ContentChunker {

    private static final int MAX_CHUNK_SIZE = 800;
    private static final int MIN_CHUNK_SIZE = 100;

    // Q&A 检测：问/答/Q/A 开头的行
    private static final Pattern QA_PATTERN = Pattern.compile(
            "^[問问Qq][：:\\s].*|^[答回AａＡ][：:\\s].*|^A[.:]\\s.*",
            Pattern.CASE_INSENSITIVE);

    // Markdown 标题
    private static final Pattern MD_HEADER = Pattern.compile("^#{1,4}\\s+");

    /**
     * 将文本分片为多个片段，自动选择最优策略
     */
    public List<Chunk> chunk(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String[] lines = text.split("\\r?\\n");

        // 1. 检测 Q&A 对模式
        if (detectQAPattern(lines)) {
            log.debug("检测到 Q&A 对模式，按每 2 行分片");
            return chunkByLineGroups(lines, 2);
        }

        // 2. 检测 Markdown 标题模式
        if (detectMarkdownHeaders(lines)) {
            log.debug("检测到 Markdown 标题，按标题分片");
            return chunkByHeaders(text, lines);
        }

        // 3. 检测逐行模式（大部分行长度适中，空行少）
        if (detectLineByLine(lines)) {
            log.debug("检测到逐行模式，按行分片");
            return chunkByLineGroups(lines, 1);
        }

        // 4. 回退到段落模式
        log.debug("使用段落模式分片");
        return chunkByParagraphs(text);
    }

    // ==================== 策略检测 ====================

    /**
     * 检测是否为 Q&A 对格式：QA 行占比超过 30% 且 Q/A 数量大致平衡
     */
    private boolean detectQAPattern(String[] lines) {
        int qCount = 0, aCount = 0, total = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            total++;
            if (QA_PATTERN.matcher(trimmed).matches()) {
                if (Character.toLowerCase(trimmed.charAt(0)) == 'q' || trimmed.startsWith("问")) {
                    qCount++;
                } else {
                    aCount++;
                }
            }
        }
        if (total == 0) return false;
        double ratio = (qCount + aCount) / (double) total;
        return ratio >= 0.3 && Math.abs(qCount - aCount) <= Math.max(qCount, aCount) * 0.6;
    }

    /**
     * 检测是否为 Markdown 文档：有 # 标题行
     */
    private boolean detectMarkdownHeaders(String[] lines) {
        int headerCount = 0;
        for (String line : lines) {
            if (MD_HEADER.matcher(line).find()) {
                headerCount++;
            }
        }
        return headerCount >= 2;
    }

    /**
     * 检测是否适合逐行分片：空行比例低，行长度比较均匀
     */
    private boolean detectLineByLine(String[] lines) {
        int emptyCount = 0;
        int totalLines = 0;
        List<Integer> lengths = new ArrayList<>();

        for (String line : lines) {
            totalLines++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                emptyCount++;
                continue;
            }
            lengths.add(trimmed.length());
        }

        if (lengths.isEmpty() || totalLines < 3) return false;

        // 空行比例低于 10% → 段落结构弱
        double emptyRatio = (double) emptyCount / totalLines;
        if (emptyRatio > 0.10) return false;

        // 平均行长度在 20~400 之间，且标准差不太大 → 行内容独立
        double avgLen = lengths.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (avgLen < 20 || avgLen > 400) return false;

        double variance = lengths.stream()
                .mapToDouble(l -> Math.pow(l - avgLen, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);
        // 标准差小于均值的 1.5 倍 → 长度分布均匀
        return stdDev < avgLen * 1.5;
    }

    // ==================== 分片策略 ====================

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
                // 空行：如果缓冲区有内容且分组完整，flush
                if (buf.length() > 0 && lineInGroup == groupSize) {
                    result.add(buildChunk(buf.toString(), result.size()));
                    buf = new StringBuilder();
                    lineInGroup = 0;
                }
                continue;
            }

            if (lineInGroup > 0) buf.append("\n");
            buf.append(trimmed);
            lineInGroup++;

            if (lineInGroup == groupSize) {
                // 检查是否需要因过大而拆分
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
        // 剩余不完整的分组
        if (buf.length() > 0) {
            result.add(buildChunk(buf.toString(), result.size()));
        }

        // 合并过小的片段
        return mergeSmallResultChunks(result);
    }

    /**
     * 按 Markdown 标题切分
     */
    private List<Chunk> chunkByHeaders(String text, String[] lines) {
        List<Chunk> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String currentTitle = null;

        for (String line : lines) {
            if (MD_HEADER.matcher(line).find()) {
                // 遇到标题：flush 上一个 section
                if (buf.length() > 0) {
                    String title = currentTitle != null ? currentTitle : "片段 " + (result.size() + 1);
                    result.addAll(contentToChunks(buf.toString().trim(), title));
                    buf = new StringBuilder();
                }
                currentTitle = line.replaceFirst("^#+\\s*", "").trim();
                continue;
            }
            if (buf.length() > 0) buf.append("\n");
            buf.append(line.trim().isEmpty() ? "" : line);
        }

        // 最后一个 section
        if (buf.length() > 0) {
            String title = currentTitle != null ? currentTitle : "片段 " + (result.size() + 1);
            result.addAll(contentToChunks(buf.toString().trim(), title));
        }

        return mergeSmallResultChunks(result);
    }

    /**
     * 段落模式（原有逻辑，简化）
     */
    private List<Chunk> chunkByParagraphs(String text) {
        String[] paragraphs = text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n");
        List<String> rawChunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            if (current.length() + trimmed.length() > MAX_CHUNK_SIZE && current.length() > MIN_CHUNK_SIZE) {
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
            String title = parts.size() == 1 ? baseTitle : baseTitle + " (" + (i + 1) + ")";
            result.add(new Chunk(title, parts.get(i)));
        }
        return result;
    }

    private List<String> mergeSmallStringChunks(List<String> chunks) {
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String chunk : chunks) {
            if (buf.length() + chunk.length() > MAX_CHUNK_SIZE && buf.length() > MIN_CHUNK_SIZE) {
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
            if (buf.length() + chunk.content.length() > MAX_CHUNK_SIZE && buf.length() > MIN_CHUNK_SIZE) {
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
            if (buf.length() + sentence.length() > MAX_CHUNK_SIZE && buf.length() > MIN_CHUNK_SIZE) {
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
