package com.faqrobot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QARequest {

    private String question;

    @Builder.Default
    private int maxResults = 5;
}
