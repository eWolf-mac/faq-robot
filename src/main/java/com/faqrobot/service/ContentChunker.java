package com.faqrobot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内容分片器 — 按段落/句子智能拆分长文本
 */
@Slf4j
@Component
public class ContentChunker {

    // 每片最大字符数
    private static final int MAX_CHUNK_SIZE = 800;
    // 每片最小字符数（低于此会与相邻合并）
    private static final int MIN_CHUNK_SIZE = 100;

    /**
     * 将文本分片为多个片段
     * @param text 原始文本
     * @return 分片列表，每个元素为一个片段标题+内容
     */
    public List<Chunk> chunk(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        // 按段落分割
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

        // 合并过小的片段
        List<String> merged = mergeSmallChunks(rawChunks);

        // 如果单个片段仍然太大，按句子拆分
        List<String> finalChunks = new ArrayList<>();
        for (String chunk : merged) {
            if (chunk.length() > MAX_CHUNK_SIZE) {
                finalChunks.addAll(splitBySentence(chunk));
            } else {
                finalChunks.add(chunk);
            }
        }

        // 构建 Chunk 对象
        List<Chunk> result = new ArrayList<>();
        for (int i = 0; i < finalChunks.size(); i++) {
            String content = finalChunks.get(i);
            String title = generateChunkTitle(content, i);
            result.add(new Chunk(title, content));
        }
        return result;
    }

    private List<String> mergeSmallChunks(List<String> chunks) {
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

    /**
     * 根据内容生成片段标题（取首行或前30字）
     */
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

    /**
     * 分片结果
     */
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
