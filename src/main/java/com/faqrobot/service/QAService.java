package com.faqrobot.service;

import com.faqrobot.model.QAResponse;
import com.faqrobot.model.SearchResult;
import com.faqrobot.repository.LuceneIndexManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QAService {

    private final LuceneIndexManager luceneIndexManager;
    private final LLMService llmService;

    /**
     * 关键词全文检索模式（原有逻辑不变）
     */
    public QAResponse answer(String question, int maxResults) {
        List<SearchResult> results = luceneIndexManager.search(question, maxResults);
        if (results.isEmpty()) {
            return QAResponse.builder().question(question)
                    .answer("未找到相关知识，请尝试其他问题。").sources(Collections.emptyList()).found(false).build();
        }
        String answer = buildAnswer(results);
        return QAResponse.builder().question(question)
                .answer(answer).sources(results).found(true).build();
    }

    /**
     * RAG 模式：Lucene 检索 → LLM 生成自然语言答案
     */
    public QAResponse answerWithLLM(String question, int maxResults) {
        List<SearchResult> results = luceneIndexManager.search(question, maxResults);
        if (results.isEmpty()) {
            return QAResponse.builder().question(question)
                    .answer("未找到相关知识，请尝试其他问题。").sources(Collections.emptyList()).found(false).build();
        }

        String llmAnswer = llmService.chat(question, results);

        if (llmAnswer != null && !llmAnswer.isEmpty()) {
            return QAResponse.builder().question(question)
                    .answer(llmAnswer).sources(results).found(true).build();
        }

        // LLM 调用失败或未配置 → 降级为关键词匹配
        String errorMsg = llmService.getLastError();
        log.info("LLM unavailable, fallback to keyword matching, error: {}", errorMsg);
        String answer = buildAnswer(results);
        return QAResponse.builder().question(question)
                .answer(answer).sources(results).found(true)
                .llmError(errorMsg != null ? errorMsg : "LLM 调用失败（未返回结果）")
                .build();
    }

    private String buildAnswer(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        if (results.size() == 1) {
            sb.append(results.get(0).getFullContent());
        } else {
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                sb.append("[").append(i + 1).append("] ").append(r.getTitle()).append("\n");
                sb.append(r.getFullContent()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    public List<SearchResult> search(String query, int maxResults) {
        return luceneIndexManager.search(query, maxResults);
    }
}
