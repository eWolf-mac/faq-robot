package com.faqrobot.controller;

import com.faqrobot.service.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class PageController {

    private final IngestionService ingestionService;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/knowledge")
    public String knowledgePage(Model model) {
        model.addAttribute("items", ingestionService.listAll());
        model.addAttribute("stats", ingestionService.getStats());
        return "knowledge";
    }
}
