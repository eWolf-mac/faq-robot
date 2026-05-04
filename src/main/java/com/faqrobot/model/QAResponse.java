package com.faqrobot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QAResponse {

    private String question;

    private String answer;

    private List<SearchResult> sources;

    private boolean found;

    /** LLM 调用失败时的错误信息（null 表示成功或无 LLM 调用） */
    private String llmError;
}
