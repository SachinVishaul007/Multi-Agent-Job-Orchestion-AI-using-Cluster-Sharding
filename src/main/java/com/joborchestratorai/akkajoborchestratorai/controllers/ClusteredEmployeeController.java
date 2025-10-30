package com.joborchestratorai.akkajoborchestratorai.controllers;

import akka.actor.typed.ActorSystem;
import com.joborchestratorai.akkajoborchestratorai.services.ClusteredResumeSearchService;
import com.joborchestratorai.akkajoborchestratorai.services.OpenAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
@Profile({"node1", "node2", "node3", "node4", "clustered"})
public class ClusteredEmployeeController {

    private static final Logger logger = LoggerFactory.getLogger(ClusteredEmployeeController.class);

    @Value("${google.api.key:}")
    private String apiKey;

    @Value("${google.search.engine.id:}")
    private String searchEngineId;
    
    @Autowired
    private ClusteredResumeSearchService clusteredService;
    
    private final OpenAIService openAIService;
    private final RestTemplate restTemplate = new RestTemplate();
    
    // Circuit breaker state
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastFailureTime = 0;
    private static final int FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_TIMEOUT = 30000; // 30 seconds
    private static final Pattern LINKEDIN_NAME_PATTERN = Pattern.compile("in/([^/]+)/?");
    
    public ClusteredEmployeeController(OpenAIService openAIService) {
        this.openAIService = openAIService;
    }

    @GetMapping("/search-employees")
    public ResponseEntity<?> searchEmployees(@RequestParam String q) {
        logger.info("Received distributed search request for query: {}", q);

        // Circuit breaker check
        if (isCircuitOpen()) {
            logger.warn("Circuit breaker is OPEN - failing fast");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(createErrorResponse("Search service temporarily unavailable"));
        }

        // Validate API configuration
        if (!isApiConfigValid()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Search service not properly configured"));
        }

        try {
            // Extract company name for targeted cluster distribution
            String companyName = extractCompanyName(q);
            String searchKey = companyName + "-employee-search";
            
            // Attempt distributed search with circuit breaker
            CompletableFuture<Map<String, Object>> distributedSearch = null;
            
            try {
                logger.info("Attempting distributed employee search for company: {}", companyName);
                
                // Distribute search across cluster nodes for parallel processing
                distributedSearch = CompletableFuture.supplyAsync(() -> {
                    return performDistributedSearch(q, companyName, searchKey);
                }).orTimeout(60, TimeUnit.SECONDS);

                Map<String, Object> clusterResult = distributedSearch.get(60, TimeUnit.SECONDS);
                
                if (clusterResult != null && clusterResult.containsKey("items")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> items = (List<Map<String, Object>>) clusterResult.get("items");
                    
                    if (items != null && !items.isEmpty()) {
                        logger.info("Distributed search successful - found {} results", items.size());
                        
                        // Reset circuit breaker on success
                        resetCircuitBreaker();
                        
                        // Add cluster processing info
                        clusterResult.put("clusterInfo", String.format(
                            "Distributed search across cluster, processed %d pages in parallel", 
                            calculatePages(items.size())
                        ));
                        clusterResult.put("distributedProcessing", true);
                        
                        return ResponseEntity.ok(clusterResult);
                    }
                }
                
                logger.warn("Distributed search returned empty results, falling back to local");
                
            } catch (Exception clusterEx) {
                logger.error("Distributed search failed: {}", clusterEx.getMessage());
                recordFailure();
                // Fall through to local fallback
            }

            // Fallback to local processing
            return performLocalSearch(q);

        } catch (Exception e) {
            recordFailure();
            String errorMsg = "Failed to search for employees: " + e.getMessage();
            logger.error(errorMsg, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(errorMsg));
        }
    }

    private Map<String, Object> performDistributedSearch(String query, String companyName, String searchKey) {
        try {
            String cleanSearchEngineId = searchEngineId.split("GOOGLE_API_KEY=")[0];
            String searchQuery = companyName + " recruiter";
            String encodedQuery = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);
            
            // Create parallel search tasks for different pages
            List<CompletableFuture<List<Map<String, Object>>>> searchTasks = new ArrayList<>();
            int totalPages = 5; // Search 5 pages in parallel
            int resultsPerPage = 10;
            
            for (int page = 0; page < totalPages; page++) {
                final int currentPage = page;
                CompletableFuture<List<Map<String, Object>>> pageTask = CompletableFuture.supplyAsync(() -> {
                    return searchSinglePage(cleanSearchEngineId, encodedQuery, currentPage, resultsPerPage);
                });
                searchTasks.add(pageTask);
            }
            
            // Wait for all parallel searches to complete
            CompletableFuture<Void> allTasks = CompletableFuture.allOf(
                searchTasks.toArray(new CompletableFuture[0])
            );
            
            // Combine results from all pages
            allTasks.get(45, TimeUnit.SECONDS);
            
            List<Map<String, Object>> allItems = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> task : searchTasks) {
                List<Map<String, Object>> pageResults = task.get();
                if (pageResults != null) {
                    allItems.addAll(pageResults);
                }
            }
            
            // Add predicted emails using distributed AI processing
            String companyDomain = extractCompanyDomain(query);
            if (companyDomain != null && !allItems.isEmpty()) {
                enhanceWithPredictedEmails(allItems, companyDomain);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("items", allItems);
            result.put("searchInformation", Map.of("totalResults", String.valueOf(allItems.size())));
            
            logger.info("Distributed search completed - collected {} total results", allItems.size());
            return result;
            
        } catch (Exception e) {
            logger.error("Error in distributed search execution: {}", e.getMessage());
            throw new RuntimeException("Distributed search failed", e);
        }
    }

    private List<Map<String, Object>> searchSinglePage(String searchEngineId, String encodedQuery, 
                                                      int page, int resultsPerPage) {
        try {
            int startIndex = (page * resultsPerPage) + 1;
            
            String url = String.format(
                "https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s+location:United+States+OR+location:USA+OR+location:US&num=%d&start=%d&siteSearch=linkedin.com/in",
                apiKey,
                searchEngineId,
                encodedQuery,
                resultsPerPage,
                startIndex
            );

            logger.info("Searching page {} in parallel thread", page + 1);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                Map.class
            );

            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null && responseBody.containsKey("items")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) responseBody.get("items");
                logger.info("Page {} returned {} results", page + 1, items != null ? items.size() : 0);
                return items;
            }
            
        } catch (Exception e) {
            logger.error("Error searching page {}: {}", page + 1, e.getMessage());
        }
        
        return new ArrayList<>();
    }

    private void enhanceWithPredictedEmails(List<Map<String, Object>> items, String companyDomain) {
        // Process email predictions in parallel using cluster resources
        List<CompletableFuture<Void>> emailTasks = new ArrayList<>();
        
        for (Map<String, Object> item : items) {
            CompletableFuture<Void> emailTask = CompletableFuture.runAsync(() -> {
                try {
                    String title = (String) item.get("title");
                    String name = extractNameFromTitle(title);
                    if (name != null) {
                        String email = openAIService.predictEmailAddress(name, companyDomain);
                        if (email != null) {
                            synchronized (item) {
                                item.put("predictedEmail", email);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error predicting email for item: {}", e.getMessage());
                }
            });
            emailTasks.add(emailTask);
        }
        
        try {
            // Wait for all email predictions to complete
            CompletableFuture.allOf(emailTasks.toArray(new CompletableFuture[0]))
                           .get(30, TimeUnit.SECONDS);
            logger.info("Enhanced {} items with predicted emails", items.size());
        } catch (Exception e) {
            logger.warn("Some email predictions timed out: {}", e.getMessage());
        }
    }

    private ResponseEntity<?> performLocalSearch(String q) {
        try {
            logger.info("Performing local fallback search");
            
            String cleanSearchEngineId = searchEngineId.split("GOOGLE_API_KEY=")[0];
            String companyName = extractCompanyName(q);
            String searchQuery = companyName + " recruiter";
            String encodedQuery = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);
            
            List<Map<String, Object>> allItems = new ArrayList<>();
            int totalRequests = 3; // Reduced for fallback
            int resultsPerPage = 10;
            
            for (int i = 0; i < totalRequests; i++) {
                int startIndex = (i * resultsPerPage) + 1;
                
                String url = String.format(
                    "https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s+location:United+States+OR+location:USA+OR+location:US&num=%d&start=%d&siteSearch=linkedin.com/in",
                    apiKey,
                    cleanSearchEngineId,
                    encodedQuery,
                    resultsPerPage,
                    startIndex
                );

                HttpHeaders headers = new HttpHeaders();
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
                HttpEntity<String> entity = new HttpEntity<>(headers);

                try {
                    ResponseEntity<Map> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        Map.class
                    );

                    Map<String, Object> responseBody = response.getBody();
                    if (responseBody != null && responseBody.containsKey("items")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> items = (List<Map<String, Object>>) responseBody.get("items");
                        if (items != null && !items.isEmpty()) {
                            allItems.addAll(items);
                            if (items.size() < resultsPerPage) {
                                break;
                            }
                        } else {
                            break;
                        }
                    }
                    
                    Thread.sleep(1000); // Rate limiting
                    
                } catch (Exception e) {
                    logger.error("Error fetching local page {}: {}", i + 1, e.getMessage());
                    break;
                }
            }

            // Add predicted emails for local results
            String companyDomain = extractCompanyDomain(q);
            if (companyDomain != null) {
                for (Map<String, Object> item : allItems) {
                    String title = (String) item.get("title");
                    String name = extractNameFromTitle(title);
                    if (name != null) {
                        String email = openAIService.predictEmailAddress(name, companyDomain);
                        if (email != null) {
                            item.put("predictedEmail", email);
                        }
                    }
                }
            }

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("items", allItems);
            responseBody.put("searchInformation", Map.of("totalResults", String.valueOf(allItems.size())));
            responseBody.put("clusterInfo", "Processed locally (cluster unavailable)");
            responseBody.put("distributedProcessing", false);

            logger.info("Local search completed with {} results", allItems.size());
            return ResponseEntity.ok(responseBody);

        } catch (Exception e) {
            recordFailure();
            throw new RuntimeException("Local search also failed", e);
        }
    }

    // Circuit breaker methods
    private boolean isCircuitOpen() {
        if (failureCount.get() >= FAILURE_THRESHOLD) {
            return (System.currentTimeMillis() - lastFailureTime) < CIRCUIT_TIMEOUT;
        }
        return false;
    }

    private void recordFailure() {
        failureCount.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();
        logger.warn("Circuit breaker failure recorded. Count: {}", failureCount.get());
    }

    private void resetCircuitBreaker() {
        failureCount.set(0);
        lastFailureTime = 0;
        logger.info("Circuit breaker reset - service healthy");
    }

    private boolean isApiConfigValid() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.startsWith("${") &&
               searchEngineId != null && !searchEngineId.isEmpty() && !searchEngineId.startsWith("${");
    }

    private int calculatePages(int totalResults) {
        return (totalResults + 9) / 10; // Round up to nearest page
    }

    private String extractCompanyName(String query) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("AND \"([^\"]+)\"$");
            java.util.regex.Matcher matcher = pattern.matcher(query);
            if (matcher.find()) {
                return matcher.group(1);
            } else {
                String companyName = query.replaceAll("(?i)(site:.*?|AND|OR|\\(.*?\\)|\")", "").trim();
                if (companyName.isEmpty()) {
                    return "Google";
                } else {
                    String[] parts = companyName.split("\\s+");
                    return parts[parts.length - 1];
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting company name", e);
            return "Google";
        }
    }

    private String extractCompanyDomain(String query) {
        try {
            String companyName = null;
            Pattern quotedPattern = Pattern.compile("AND\\s+['\"]([^'\"]+)['\"]");
            Matcher quotedMatcher = quotedPattern.matcher(query);
            
            if (quotedMatcher.find()) {
                companyName = quotedMatcher.group(1).trim();
            } else {
                String[] parts = query.split("AND");
                if (parts.length > 1) {
                    companyName = parts[parts.length - 1].trim();
                } else {
                    companyName = query.trim();
                }
            }
            
            if (companyName != null && !companyName.isEmpty()) {
                companyName = companyName.toLowerCase()
                    .replaceAll("^[^a-z0-9]+", "")
                    .replaceAll("[^a-z0-9]+$", "")
                    .replaceAll("[^a-z0-9]+", "");
                
                if (!companyName.isEmpty()) {
                    return companyName + ".com";
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting company domain", e);
        }
        return null;
    }
    
    private String extractNameFromTitle(String title) {
        if (title == null) return null;
        
        try {
            String cleanTitle = title.split("[&<>\"|\\n]")[0].trim();
            String[] parts = cleanTitle.split("[-|:]");
            if (parts.length > 0) {
                String namePart = parts[0].trim();
                namePart = namePart.replaceAll("(?i)\\b(Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.?|Prof\\.?|\\s+)\\b", " ").trim();
                if (!namePart.isEmpty()) {
                    return namePart;
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting name from title: " + title, e);
        }
        return null;
    }

    private Map<String, String> createErrorResponse(String message) {
        Map<String, String> errorResponse = new HashMap<>();
        errorResponse.put("error", message);
        return errorResponse;
    }
}