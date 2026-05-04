package com.faqrobot.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 关键词提取器 — 基于 Lucene SmartChineseAnalyzer + TF 统计
 */
@Slf4j
@Component
public class KeywordExtractor {

    private static final int MAX_KEYWORDS = 8;
    private static final int MIN_TERM_LENGTH = 2;

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
            "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
            "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "些",
            "所", "因为", "所以", "但是", "然而", "而且", "虽然", "如果", "可以",
            "这个", "那个", "什么", "怎么", "哪", "为什么", "如何", "多少",
            "吗", "呢", "吧", "啊", "哦", "嗯", "哈", "呀", "哇",
            "与", "及", "或", "被", "把", "从", "以", "对", "向", "往", "用",
            "能", "能够", "可能", "应该", "需要", "已经", "正在", "将", "还",
            "来", "去", "做", "让", "给", "请", "叫", "使", "想", "知道",
            "觉得", "认为", "这种", "那种", "各种", "每个", "所有", "任何",
            "一些", "一点", "有点", "这里", "那里", "哪里", "时候", "之后",
            "之前", "以后", "然后", "接着", "首先", "最后", "另外",
            "通过", "根据", "关于", "对于", "为了", "由于", "按照", "除了",
            "等等", "就是", "还是", "只是", "不过", "还是", "而且"
    ));

    /**
     * 从文本中提取关键词，返回逗号分隔的关键词字符串
     */
    public String extract(String text) {
        return extractAsList(text).stream()
                .collect(Collectors.joining(","));
    }

    /**
     * 从文本中提取关键词列表
     */
    public List<String> extractAsList(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Integer> tf = new LinkedHashMap<>();
        try (SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer()) {
            org.apache.lucene.analysis.TokenStream ts = analyzer.tokenStream("content", new StringReader(text));
            CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                String term = termAttr.toString().trim();
                if (term.length() >= MIN_TERM_LENGTH && !STOP_WORDS.contains(term)) {
                    tf.put(term, tf.getOrDefault(term, 0) + 1);
                }
            }
            ts.end();
            ts.close();
        } catch (IOException e) {
            log.warn("keyword extraction error: {}", e.getMessage());
            return Collections.emptyList();
        }
        return tf.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_KEYWORDS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 从多段文本中汇总提取全局关键词
     */
    public List<String> extractGlobalKeywords(List<String> texts) {
        Map<String, Integer> dfMap = new HashMap<>();
        Map<String, Integer> tfMap = new HashMap<>();
        for (String text : texts) {
            Set<String> seen = new HashSet<>();
            try (SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer()) {
                org.apache.lucene.analysis.TokenStream ts = analyzer.tokenStream("content", new StringReader(text));
                CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
                ts.reset();
                while (ts.incrementToken()) {
                    String term = termAttr.toString().trim();
                    if (term.length() >= MIN_TERM_LENGTH && !STOP_WORDS.contains(term)) {
                        tfMap.put(term, tfMap.getOrDefault(term, 0) + 1);
                        if (!seen.contains(term)) {
                            dfMap.put(term, dfMap.getOrDefault(term, 0) + 1);
                            seen.add(term);
                        }
                    }
                }
                ts.end();
                ts.close();
            } catch (IOException e) {
                log.warn("global keyword extraction error: {}", e.getMessage());
            }
        }
        // TF-IDF 简单加权
        int docCount = texts.size();
        return tfMap.entrySet().stream()
                .sorted((a, b) -> {
                    double scoreA = a.getValue() * Math.log((double) docCount / (dfMap.getOrDefault(a.getKey(), 1)));
                    double scoreB = b.getValue() * Math.log((double) docCount / (dfMap.getOrDefault(b.getKey(), 1)));
                    return Double.compare(scoreB, scoreA);
                })
                .limit(MAX_KEYWORDS * 3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
