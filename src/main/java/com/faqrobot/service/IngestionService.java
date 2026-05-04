package com.faqrobot.service;

import com.faqrobot.model.KnowledgeItem;
import com.faqrobot.repository.KnowledgeRepository;
import com.faqrobot.repository.LuceneIndexManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final KnowledgeRepository knowledgeRepository;
    private final LuceneIndexManager luceneIndexManager;
    private final KeywordExtractor keywordExtractor;
    private final ContentChunker contentChunker;
    private final FileTextExtractor fileTextExtractor;

    @Transactional
    public KnowledgeItem ingest(String title, String content, String category, String source) {
        KnowledgeItem item = KnowledgeItem.builder()
                .title(title).content(content).category(category)
                .source(source != null ? source : "manual").build();
        KnowledgeItem saved = knowledgeRepository.save(item);
        luceneIndexManager.addDocument(saved);
        return saved;
    }

    @Transactional
    public int ingestBatch(List<KnowledgeItem> items) {
        int count = 0;
        for (KnowledgeItem item : items) {
            KnowledgeItem saved = knowledgeRepository.save(item);
            luceneIndexManager.addDocument(saved);
            count++;
        }
        return count;
    }

    @Transactional
    public KnowledgeItem update(Long id, String title, String content, String category) {
        KnowledgeItem item = knowledgeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("not found"));
        if (title != null) item.setTitle(title);
        if (content != null) item.setContent(content);
        if (category != null) item.setCategory(category);
        KnowledgeItem saved = knowledgeRepository.save(item);
        luceneIndexManager.updateDocument(saved);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        List<KnowledgeItem> children = knowledgeRepository.findByParentId(id);
        for (KnowledgeItem child : children) {
            knowledgeRepository.delete(child);
            luceneIndexManager.deleteDocument(child.getId());
        }
        knowledgeRepository.deleteById(id);
        luceneIndexManager.deleteDocument(id);
    }

    /**
     * 删除文件导入及其所有分片（统一删除）
     * @return 删除的分片总数（不含父条目）
     */
    @Transactional
    public int deleteFileImport(Long parentId) {
        List<KnowledgeItem> children = knowledgeRepository.findByParentId(parentId);
        int chunkCount = children.size();
        for (KnowledgeItem child : children) {
            knowledgeRepository.delete(child);
            luceneIndexManager.deleteDocument(child.getId());
        }
        knowledgeRepository.deleteById(parentId);
        luceneIndexManager.deleteDocument(parentId);
        log.info("文件导入已删除: parentId={}, 分片数={}", parentId, chunkCount);
        return chunkCount;
    }

    public List<KnowledgeItem> listAll() {
        return knowledgeRepository.findAll();
    }

    public List<KnowledgeItem> listByCategory(String category) {
        return knowledgeRepository.findByCategory(category);
    }

    public void rebuildIndex() {
        luceneIndexManager.rebuildIndex(knowledgeRepository.findAll());
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("knowledge_count", knowledgeRepository.count());
        stats.put("index_count", luceneIndexManager.getDocumentCount());
        return stats;
    }

    /**
     * 文件导入：自动分片、提取关键词、批量入库
     */
    @Transactional
    public Map<String, Object> importFile(String fileName, InputStream inputStream) throws IOException {
        log.info("========== 开始导入文件: {} ==========", fileName);
        long startTime = System.currentTimeMillis();

        log.info("[步骤1/4] 提取文件文本内容...");
        String text = fileTextExtractor.extract(inputStream, fileName);
        if (text == null || text.trim().isEmpty()) {
            log.error("文件内容为空或无法解析: {}", fileName);
            throw new RuntimeException("文件内容为空或无法解析");
        }
        log.info("[步骤1/4] 文本提取完成，共 {} 字符", text.length());
        // 诊断：输出前200字符，方便一眼判断是否乱码
        log.info("  提取内容前200字: {}", text.substring(0, Math.min(200, text.length())));

        String baseCategory = extractCategoryFromFileName(fileName);

        log.info("[步骤2/4] 内容分片中...");
        List<ContentChunker.Chunk> chunks = contentChunker.chunk(text);
        if (chunks.isEmpty()) {
            log.error("分片结果为空: {}", fileName);
            throw new RuntimeException("文件内容为空或无法解析");
        }
        log.info("[步骤2/4] 分片完成，共 {} 个分片", chunks.size());

        // 全局关键词提取
        log.info("[步骤3/4] 提取全局关键词...");
        List<String> allContents = chunks.stream()
                .map(ContentChunker.Chunk::getContent)
                .collect(Collectors.toList());
        List<String> globalKeywords = keywordExtractor.extractGlobalKeywords(allContents);
        log.info("[步骤3/4] 全局关键词: {}", globalKeywords);

        // 第一个分片作为父条目（代表整个文件）
        KnowledgeItem parent = KnowledgeItem.builder()
                .title(fileName)
                .content(text.length() > 500 ? text.substring(0, 500) + "..." : text)
                .category(baseCategory)
                .source("file:" + fileName)
                .keywords(String.join(",", globalKeywords))
                .build();
        log.info("[步骤4/4] 保存父条目到数据库和索引...");
        parent = knowledgeRepository.save(parent);
        luceneIndexManager.addDocument(parent);
        log.info("[步骤4/4] 父条目已保存, ID={}", parent.getId());

        // 每个分片作为子条目
        List<KnowledgeItem> childItems = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ContentChunker.Chunk chunk = chunks.get(i);
            String kw = keywordExtractor.extract(chunk.getContent());
            KnowledgeItem child = KnowledgeItem.builder()
                    .title(chunk.getTitle())
                    .content(chunk.getContent())
                    .category(baseCategory)
                    .source("file:" + fileName)
                    .keywords(kw)
                    .parentId(parent.getId())
                    .build();
            KnowledgeItem saved = knowledgeRepository.save(child);
            luceneIndexManager.addDocument(saved);
            childItems.add(saved);
            log.debug("  子分片[{}/{}] 已保存, ID={}, 标题={}", i + 1, chunks.size(), saved.getId(), chunk.getTitle());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("========== 导入完成: {} → {} 个分片, 耗时 {}ms ==========", fileName, childItems.size(), elapsed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parent", parent);
        result.put("chunks", childItems);
        result.put("chunkCount", childItems.size());
        result.put("globalKeywords", globalKeywords);
        return result;
    }

    /**
     * 获取热门关键词（按出现频率排序，取前10）
     */
    public List<String> getAllKeywords() {
        return knowledgeRepository.findAll().stream()
                .map(KnowledgeItem::getKeywords)
                .filter(k -> k != null && !k.isEmpty())
                .flatMap(k -> Arrays.stream(k.split(",")))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 按关键词搜索
     */
    public List<KnowledgeItem> findByKeyword(String keyword) {
        return knowledgeRepository.findByKeywordsContaining(keyword);
    }

    /**
     * 获取文件导入的父条目（顶层文档）
     */
    public List<KnowledgeItem> listRootDocuments() {
        return knowledgeRepository.findAll().stream()
                .filter(item -> item.getParentId() == null)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有文件导入记录（含分片数量），用于前端统一删除
     */
    public List<Map<String, Object>> listFileImports() {
        return knowledgeRepository.findAll().stream()
                .filter(item -> item.getParentId() == null
                        && item.getSource() != null
                        && item.getSource().startsWith("file:"))
                .map(parent -> {
                    List<KnowledgeItem> children = knowledgeRepository.findByParentId(parent.getId());
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", parent.getId());
                    info.put("fileName", parent.getTitle());
                    info.put("category", parent.getCategory());
                    info.put("keywords", parent.getKeywords());
                    info.put("chunkCount", children.size());
                    info.put("createdAt", parent.getCreatedAt());
                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取某个父条目下的所有分片
     */
    public List<KnowledgeItem> listChunks(Long parentId) {
        return knowledgeRepository.findByParentId(parentId);
    }

    private String extractCategoryFromFileName(String fileName) {
        if (fileName == null) return "未分类";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "文档";
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "Word";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "Excel";
        return "导入";
    }
}

