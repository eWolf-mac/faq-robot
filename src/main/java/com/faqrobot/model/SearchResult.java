package com.faqrobot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchResult {

    private Long knowledgeId;

    private String title;

    private String snippet;

    private float score;

    private String category;

    private String fullContent;
}
