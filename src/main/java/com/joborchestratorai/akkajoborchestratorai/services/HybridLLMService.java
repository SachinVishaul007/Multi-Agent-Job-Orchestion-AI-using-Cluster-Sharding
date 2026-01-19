package com.joborchestratorai.akkajoborchestratorai.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class HybridLLMService {
    private static final Logger log = LoggerFactory.getLogger(HybridLLMService.class);

    @Value("${llm.local.enabled:true}")
    private boolean localLLMEnabled;

    @Value("${llm.local.url:http://localhost:11434}")
    private String localLLMUrl;

    @Value("${llm.local.model:llama3.2:3b}")
    private String localLLMModel;

    @Value("${llm.local.timeout:30000}")
    private int localLLMTimeout;

    @Autowired
    private OpenAIService openAIService;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Main method for processing text with hybrid approach
     */
    public String processTextWithPrompt(String prompt) {
        if (localLLMEnabled) {
            try {
                log.info("Attempting local LLM inference with model: {}", localLLMModel);
                String result = callLocalLLM(prompt);
                if (result != null && !result.trim().isEmpty()) {
                    log.info("Local LLM inference successful");
                    return result;
                }
            } catch (Exception e) {
                log.warn("Local LLM failed, falling back to OpenAI: {}", e.getMessage());
            }
        }

        log.info("Using OpenAI fallback");
        return openAIService.processTextWithPrompt(prompt);
    }

    /**
     * Streaming method with hybrid approach - prioritizes local LLM using non-streaming
     */
    public void callLLMStream(String prompt, Consumer<String> chunkCallback) {
        if (localLLMEnabled) {
            try {
                log.info("Using local LLM non-streaming with model: {} (faster than streaming)", localLLMModel);
                // Use non-streaming local LLM and send result as single chunk
                String result = callLocalLLM(prompt);
                if (result != null && !result.trim().isEmpty()) {
                    log.info("Local LLM non-streaming successful, sending complete response");
                    chunkCallback.accept(result);
                    return;
                }
            } catch (Exception e) {
                log.warn("Local LLM failed, falling back to OpenAI streaming: {}", e.getMessage());
            }
        }

        log.info("Using OpenAI streaming fallback");
        openAIService.callOpenAIStream(prompt, chunkCallback);
    }

    /**
     * Call local LLM via Ollama API
     */
    private String callLocalLLM(String prompt) {
        try {
            // Test connection first
            if (!isLocalLLMAvailable()) {
                throw new RuntimeException("Local LLM not available at " + localLLMUrl);
            }

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", localLLMModel);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);
            requestBody.put("options", Map.of(
                "temperature", 0.7,
                "top_p", 0.9,
                "max_tokens", 4000
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

            String url = localLLMUrl + "/api/generate";
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, requestEntity, String.class
            );

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new RuntimeException("Invalid response from local LLM: " + response.getStatusCode());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("response").asText("");

        } catch (Exception e) {
            throw new RuntimeException("Error calling local LLM: " + e.getMessage(), e);
        }
    }

    /**
     * Call local LLM with streaming
     */
    private void callLocalLLMStream(String prompt, Consumer<String> chunkCallback) {
        try {
            if (!isLocalLLMAvailable()) {
                throw new RuntimeException("Local LLM not available at " + localLLMUrl);
            }

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", localLLMModel);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", true);
            requestBody.put("options", Map.of(
                "temperature", 0.7,
                "top_p", 0.9,
                "max_tokens", 4000
            ));

            java.net.URL url = new java.net.URL(localLLMUrl + "/api/generate");
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setReadTimeout(localLLMTimeout);

            try (java.io.OutputStream os = connection.getOutputStream()) {
                byte[] input = objectMapper.writeValueAsBytes(requestBody);
                os.write(input, 0, input.length);
            }

            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonNode chunk = objectMapper.readTree(line);
                        if (chunk.has("response")) {
                            String token = chunk.path("response").asText();
                            if (!token.isEmpty()) {
                                chunkCallback.accept(token);
                            }
                        }
                        if (chunk.path("done").asBoolean(false)) {
                            break;
                        }
                    } catch (Exception e) {
                        log.debug("Skipping malformed chunk: {}", line);
                    }
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Error in streaming local LLM: " + e.getMessage(), e);
        }
    }

    /**
     * Check if local LLM is available
     */
    public boolean isLocalLLMAvailable() {
        try {
            String url = localLLMUrl + "/api/tags";
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, null, String.class
            );
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.debug("Local LLM not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if the specified model is available locally
     */
    public boolean isModelAvailable() {
        try {
            if (!isLocalLLMAvailable()) {
                return false;
            }

            String url = localLLMUrl + "/api/tags";
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, null, String.class
            );

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                return false;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode models = root.path("models");

            if (models.isArray()) {
                for (JsonNode model : models) {
                    String modelName = model.path("name").asText();
                    if (modelName.startsWith(localLLMModel)) {
                        log.info("Found local model: {}", modelName);
                        return true;
                    }
                }
            }

            log.warn("Model {} not found locally. Available models: {}", localLLMModel, models);
            return false;

        } catch (Exception e) {
            log.error("Error checking model availability: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Pull the specified model if not available
     */
    public void pullModel() {
        try {
            log.info("Pulling model: {}", localLLMModel);

            Map<String, Object> requestBody = Map.of("name", localLLMModel);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

            String url = localLLMUrl + "/api/pull";
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, requestEntity, String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("Model pull initiated successfully");
            } else {
                log.error("Failed to pull model: {}", response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Error pulling model: {}", e.getMessage());
        }
    }

    /**
     * Get status information about the hybrid LLM setup
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        
        status.put("localEnabled", localLLMEnabled);
        status.put("localUrl", localLLMUrl);
        status.put("localModel", localLLMModel);
        
        boolean localAvailable = isLocalLLMAvailable();
        status.put("localAvailable", localAvailable);
        
        if (localAvailable) {
            status.put("modelAvailable", isModelAvailable());
        }
        
        status.put("fallbackToOpenAI", !localAvailable || !localLLMEnabled);
        
        return status;
    }
}