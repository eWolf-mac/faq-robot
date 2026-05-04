package com.faqrobot.service;

import com.faqrobot.config.LLMConfig;
import com.faqrobot.model.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * LLM 调用服务 — OpenAI 兼容 Chat Completions API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    private final LLMConfig llmConfig;
    private final RestTemplate llmRestTemplate;

    /** 最近一次 LLM 调用失败的原因 */
    private volatile String lastError;

    private static final String SYSTEM_PROMPT =
            "你是一个专业的知识库问答助手。请严格根据以下【参考资料】回答用户的问题。\n" +
            "要求：\n" +
            "1. 如果参考资料中有答案，请用流畅自然的中文总结后回答\n" +
            "2. 引用具体来源时标注编号，如 [1]\n" +
            "3. 如果参考资料不足以回答问题，请明确告知用户\n" +
            "4. 不要编造参考资料中没有的信息";

    /**
     * RAG 问答：检索结果作为上下文喂给 LLM 生成答案
     */
    public String chat(String question, List<SearchResult> context) {
        if (!llmConfig.isEnabled()) {
            log.warn("LLM disabled, fallback to keyword mode");
            lastError = "LLM 未启用（enabled: false）";
            return null;
        }

        String contextText = buildContext(context);
        String userMessage = "参考资料：\n" + contextText + "\n\n用户问题：" + question;

        Map<String, Object> requestBody = buildRequestBody(userMessage);
        String url = llmConfig.getApiUrl() + llmConfig.getChatPath();

        log.info("LLM request -> {} | model={} | context_chars={} | key_prefix={}",
                url, llmConfig.getModel(), contextText.length(),
                llmConfig.getApiKey() != null && llmConfig.getApiKey().length() > 8
                        ? llmConfig.getApiKey().substring(0, 8) + "..." : "N/A");

        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + llmConfig.getApiKey());
            headers.set("Content-Type", "application/json");

            org.springframework.http.HttpEntity<Map<String, Object>> entity =
                    new org.springframework.http.HttpEntity<>(requestBody, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = llmRestTemplate.postForObject(url, entity, Map.class);

            if (response == null) {
                log.error("LLM response null");
                lastError = "LLM 返回空响应";
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("LLM no choices: {}", response);
                lastError = "LLM 无有效返回内容: " + response;
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            log.info("LLM answered: {} chars, model: {}", content != null ? content.length() : 0, llmConfig.getModel());
            return content;

        } catch (Exception e) {
            log.error("LLM call failed: url={}, error={}", url, e.getMessage());
            lastError = "LLM 调用异常: " + e.getMessage();
            return null;
        }
    }

    private Map<String, Object> buildRequestBody(String userMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llmConfig.getModel());
        body.put("temperature", 0.3);
        body.put("max_tokens", 1000);

        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);
        messages.add(systemMsg);

        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        body.put("messages", messages);
        return body;
    }

    private String buildContext(List<SearchResult> context) {
        if (context == null || context.isEmpty()) return "无参考资料";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < context.size(); i++) {
            SearchResult r = context.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append(r.getTitle()).append("\n");
            sb.append(r.getFullContent()).append("\n\n");
        }
        return sb.toString();
    }

    /** 获取最近一次 LLM 调用失败的描述，成功时返回 null */
    public String getLastError() {
        return lastError;
    }
}
