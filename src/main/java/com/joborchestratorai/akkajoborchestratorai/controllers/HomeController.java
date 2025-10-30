package com.joborchestratorai.akkajoborchestratorai.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/test")
    public String indexHtml() {
        return "indextest";
    }

    @GetMapping("/bulk-email")
    public String bulkEmail() {
        return "bulk-email";
    }

}