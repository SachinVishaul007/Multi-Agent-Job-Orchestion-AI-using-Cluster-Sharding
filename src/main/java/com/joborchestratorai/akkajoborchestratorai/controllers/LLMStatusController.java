package com.joborchestratorai.akkajoborchestratorai.controllers;

import com.joborchestratorai.akkajoborchestratorai.services.HybridLLMService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class LLMStatusController {

    @Autowired
    private HybridLLMService hybridLLMService;

    @GetMapping("/llm-status")
    public ResponseEntity<Map<String, Object>> getLLMStatus() {
        Map<String, Object> status = hybridLLMService.getStatus();
        return ResponseEntity.ok(status);
    }

    @PostMapping("/pull-model")
    public ResponseEntity<Map<String, String>> pullModel() {
        try {
            if (!hybridLLMService.isLocalLLMAvailable()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Local LLM service not available. Please ensure Ollama is running."
                ));
            }

            hybridLLMService.pullModel();
            return ResponseEntity.ok(Map.of(
                "message", "Model pull initiated. This may take a few minutes depending on model size."
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to pull model: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/test-local-llm")
    public ResponseEntity<Map<String, Object>> testLocalLLM() {
        try {
            String testPrompt = "Respond with 'Local LLM is working!' to confirm the connection.";
            String result = hybridLLMService.processTextWithPrompt(testPrompt);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "response", result,
                "status", hybridLLMService.getStatus()
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", e.getMessage(),
                "status", hybridLLMService.getStatus()
            ));
        }
    }
}