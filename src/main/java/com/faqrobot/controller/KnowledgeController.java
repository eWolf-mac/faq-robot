package com.faqrobot.controller;

import com.faqrobot.model.KnowledgeItem;
import com.faqrobot.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final IngestionService ingestionService;

    @PostMapping("/ingest")
    public ResponseEntity<KnowledgeItem> ingest(@RequestBody Map<String, String> body) {
        String title = body.get("title");
        String content = body.get("content");
        String category = body.getOrDefault("category", "未分类");
        String source = body.getOrDefault("source", "手动输入");
        if (title == null || title.trim().isEmpty()) return ResponseEntity.badRequest().build();
        if (content == null || content.trim().isEmpty()) return ResponseEntity.badRequest().build();
        KnowledgeItem saved = ingestionService.ingest(title, content, category, source);
        return ResponseEntity.ok(saved);
    }

    /**
     * 文件导入 — 支持 .txt/.md/.docx/.xlsx/.xls 文件，自动分片 + 关键词提取
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            log.warn("导入请求：文件为空");
            return ResponseEntity.badRequest().build();
        }
        log.info("收到文件导入请求: {} (大小: {} bytes)", file.getOriginalFilename(), file.getSize());
        try {
            Map<String, Object> result = ingestionService.importFile(
                    file.getOriginalFilename(), file.getInputStream());
            log.info("文件导入成功: {}, 分片数: {}", file.getOriginalFilename(), result.get("chunkCount"));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("文件导入失败: {} - {}", file.getOriginalFilename(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeItem> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try { return ResponseEntity.ok(ingestionService.update(id, body.get("title"), body.get("content"), body.get("category"))); }
        catch (RuntimeException e) { return ResponseEntity.notFound().build(); }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        try { ingestionService.delete(id); return ResponseEntity.ok().build(); }
        catch (Exception e) { return ResponseEntity.notFound().build(); }
    }

    @GetMapping("/list")
    public ResponseEntity<List<KnowledgeItem>> listAll() {
        return ResponseEntity.ok(ingestionService.listAll());
    }

    @GetMapping("/list/{category}")
    public ResponseEntity<List<KnowledgeItem>> listByCategory(@PathVariable String category) {
        return ResponseEntity.ok(ingestionService.listByCategory(category));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(ingestionService.getStats());
    }

    @PostMapping("/rebuild-index")
    public ResponseEntity<String> rebuildIndex() {
        ingestionService.rebuildIndex();
        return ResponseEntity.ok("索引重建完成");
    }

    /**
     * 获取所有关键词（去重排序，用于前端关键词云）
     */
    @GetMapping("/keywords")
    public ResponseEntity<List<String>> getAllKeywords() {
        return ResponseEntity.ok(ingestionService.getAllKeywords());
    }

    /**
     * 按关键词搜索知识条目
     */
    @GetMapping("/by-keyword")
    public ResponseEntity<List<KnowledgeItem>> findByKeyword(@RequestParam String kw) {
        return ResponseEntity.ok(ingestionService.findByKeyword(kw));
    }

    /**
     * 获取某个文档的所有分片
     */
    @GetMapping("/chunks/{parentId}")
    public ResponseEntity<List<KnowledgeItem>> listChunks(@PathVariable Long parentId) {
        return ResponseEntity.ok(ingestionService.listChunks(parentId));
    }

    /**
     * 获取所有文件导入记录（含分片数），用于前端统一管理
     */
    @GetMapping("/files")
    public ResponseEntity<List<Map<String, Object>>> listFileImports() {
        return ResponseEntity.ok(ingestionService.listFileImports());
    }

    /**
     * 统一删除某个文件导入及其所有分片
     */
    @DeleteMapping("/files/{parentId}")
    public ResponseEntity<Map<String, Object>> deleteFileImport(@PathVariable Long parentId) {
        try {
            int chunkCount = ingestionService.deleteFileImport(parentId);
            log.info("文件导入统一删除完成: parentId={}, 分片数={}", parentId, chunkCount);
            return ResponseEntity.ok(java.util.Collections.singletonMap("deletedChunks", chunkCount));
        } catch (Exception e) {
            log.error("文件导入删除失败: parentId={}", parentId, e);
            return ResponseEntity.notFound().build();
        }
    }
}
