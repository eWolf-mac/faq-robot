package com.faqrobot.controller;

import com.faqrobot.model.QARequest;
import com.faqrobot.model.QAResponse;
import com.faqrobot.model.SearchResult;
import com.faqrobot.service.QAService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/qa")
@RequiredArgsConstructor
public class QAController {

    private final QAService qaService;

    /**
     * 关键词全文检索问答
     */
    @PostMapping("/ask")
    public ResponseEntity<QAResponse> ask(@RequestBody QARequest request) {
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        QAResponse response = qaService.answer(request.getQuestion(), request.getMaxResults() > 0 ? request.getMaxResults() : 5);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/ask")
    public ResponseEntity<QAResponse> askGet(@RequestParam String q, @RequestParam(defaultValue = "5") int max) {
        return ResponseEntity.ok(qaService.answer(q, max));
    }

    /**
     * RAG 模式：Lucene 检索 + LLM 生成自然语言答案
     */
    @PostMapping("/ask-llm")
    public ResponseEntity<QAResponse> askLLM(@RequestBody QARequest request) {
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        QAResponse response = qaService.answerWithLLM(request.getQuestion(),
                request.getMaxResults() > 0 ? request.getMaxResults() : 5);
        return ResponseEntity.ok(response);
    }

    /**
     * 全文搜索（仅返回匹配条目，不做问答）
     */
    @PostMapping("/search")
    public ResponseEntity<List<SearchResult>> search(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        int max = body.containsKey("maxResults") ? ((Number) body.get("maxResults")).intValue() : 5;
        if (query == null || query.trim().isEmpty()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(qaService.search(query, max));
    }
}
