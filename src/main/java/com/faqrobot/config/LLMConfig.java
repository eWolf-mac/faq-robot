package com.faqrobot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LLMConfig {

    private String apiUrl;
    private String apiKey;
    private String model = "deepseek-chat";
    private boolean enabled = false;
    /**
     * Chat Completions 端点路径，默认 OpenAI 标准 /v1/chat/completions。
     * 智谱: /chat/completions（搭配 api-url: https://open.bigmodel.cn/api/paas/v4）
     */
    private String chatPath = "/v1/chat/completions";

    @Bean
    public RestTemplate llmRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }
}
