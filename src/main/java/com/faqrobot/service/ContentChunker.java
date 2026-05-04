package com.faqrobot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内容分片器 — 自动检测内容结构并选择最优分片策略
 *
 * <p>基于结构特征（而非关键词）检测：
 * <ol>
 *   <li>二行组模式 — 非空行成对出现（常见于 Q&A 文档），按每 2 行分片</li>
 *   <li>Markdown 标题模式 — 检测到 # 标题，按标题切分</li>
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

    /**
     * 将文本分片为多个片段，自动选择最优策略
     */
    public List<Chunk> chunk(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String[] lines = text.split("\\r?\\n");

        // 1. 检测 Markdown 标题模式（优先，因为标题是明确的边界信号）
        if (detectMarkdownHeaders(lines)) {
            log.debug("检测到 Markdown 标题，按标题分片");
            return chunkByHeaders(lines);
        }

        // 2. 分析非空行的连续段结构
        int[] runHistogram = buildRunHistogram(lines); // [1-行段, 2-行段, 3+-行段]
        int totalGrouped = runHistogram[0] + runHistogram[1] + runHistogram[2];
        if (totalGrouped == 0) return Collections.emptyList();

        // 3. 二行组为主 → Q&A / 成对结构
        double pairRatio = (double) runHistogram[1] / totalGrouped;
        if (pairRatio >= 0.4) {
            log.debug("检测到二行组结构 (占比 {:.0%})，按每 2 行分片", pairRatio);
            return chunkByLineGroups(lines, 2);
        }

        // 4. 孤行为主 + 弱段落结构 → 逐行模式
        double singleRatio = (double) runHistogram[0] / totalGrouped;
        double emptyRatio = calcEmptyRatio(lines);
        if (singleRatio >= 0.5 && emptyRatio < 0.15) {
            log.debug("检测到逐行结构，按行分片");
            return chunkByLineGroups(lines, 1);
        }

        // 5. 回退到段落模式
        log.debug("使用段落模式分片 (二行组 {:.0%}, 孤行 {:.0%}, 空行 {:.0%})",
                pairRatio, singleRatio, emptyRatio);
        return chunkByParagraphs(text);
    }

    // ==================== 结构检测 ====================

    /**
     * 统计非空行连续段的长度分布。
     * 返回 int[3]: [长度为1的段数, 长度为2的段数, 长度>=3的段数]
     */
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

    private boolean detectMarkdownHeaders(String[] lines) {
        int headerCount = 0;
        for (String line : lines) {
            if (MD_HEADER.matcher(line).find()) {
                headerCount++;
            }
        }
        return headerCount >= 2;
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
        // 剩余不完整的分组
        if (buf.length() > 0) {
            result.add(buildChunk(buf.toString(), result.size()));
        }

        return mergeSmallResultChunks(result);
    }

    private void flushGroupIfFull(List<Chunk> result, StringBuilder buf, int lineInGroup, int groupSize) {
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

        if (buf.length() > 0) {
            String title = currentTitle != null ? currentTitle : "片段 " + (result.size() + 1);
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
