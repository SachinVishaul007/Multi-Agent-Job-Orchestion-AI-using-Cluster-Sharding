package com.joborchestratorai.akkajoborchestratorai.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.joborchestratorai.akkajoborchestratorai.services.OpenAIService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
@Profile("!clustered & !node1 & !node2 & !node3 & !node4")
public class EmployeeSearchController {

    private static final Logger logger = LoggerFactory.getLogger(EmployeeSearchController.class);

    @Value("${google.api.key:}")
    private String apiKey;

    @Value("${google.search.engine.id:}")
    private String searchEngineId;
    
    private final OpenAIService openAIService;
    private static final Pattern LINKEDIN_NAME_PATTERN = Pattern.compile("in/([^/]+)/?");
    
    public EmployeeSearchController(OpenAIService openAIService) {
        this.openAIService = openAIService;
    }

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/search-employees")
    public ResponseEntity<?> searchEmployees(@RequestParam String q) {
        logger.info("Received search request for query: {}", q);

        // Validate API key and search engine ID
        if (apiKey == null || apiKey.isEmpty() || apiKey.startsWith("${")) {
            String errorMsg = "Google API key is not properly configured";
            logger.error(errorMsg);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(errorMsg));
        }

        if (searchEngineId == null || searchEngineId.isEmpty() || searchEngineId.startsWith("${")) {
            String errorMsg = "Google Search Engine ID is not properly configured";
            logger.error(errorMsg);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(errorMsg));
        }

        try {
            // Clean up the search engine ID (remove any trailing API key if present)
            String cleanSearchEngineId = searchEngineId.split("GOOGLE_API_KEY=")[0];
            
            // Extract just the company name from the query
            String companyName = "Google";  // Default fallback
            
            // Try to extract the company name from the query
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("AND \"([^\"]+)\"$");
            java.util.regex.Matcher matcher = pattern.matcher(q);
            if (matcher.find()) {
                companyName = matcher.group(1);
            } else {
                // If pattern matching fails, just take the last word as company name
                companyName = q.replaceAll("(?i)(site:.*?|AND|OR|\\(.*?\\)|\")", "").trim();
                if (companyName.isEmpty()) {
                    companyName = "Google";  // Fallback if everything is removed
                } else {
                    // Take the last non-empty part as company name
                    String[] parts = companyName.split("\\s+");
                    companyName = parts[parts.length - 1];
                }
            }
            
            // Create a simple search query with just the company name
            String searchQuery = companyName + " recruiter";
            
            // Encode the query for URL
            String encodedQuery = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);
            
            // Initialize list to store all items from all pages
            List<Map<String, Object>> allItems = new ArrayList<>();
            int totalRequests = 5; // Number of pages to fetch (5 pages * 10 results = 50 results)
            int resultsPerPage = 10; // Max results per page allowed by API
            
            // Make multiple requests to get more results
            for (int i = 0; i < totalRequests; i++) {
                int startIndex = (i * resultsPerPage) + 1; // Google's API is 1-based
                
                // Build the Google Custom Search API URL with pagination and location filter
                String url = String.format(
                        "https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s+location:United+States+OR+location:USA+OR+location:US&num=%d&start=%d&siteSearch=linkedin.com/in",
                        apiKey,
                        cleanSearchEngineId,
                        encodedQuery,
                        resultsPerPage,
                        startIndex
                );

                // Log the actual URL being called (with key masked for security)
                String maskedUrl = String.format(
                        "https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s&num=%d&start=%d&siteSearch=linkedin.com/in",
                        "[REDACTED]",
                        cleanSearchEngineId,
                        encodedQuery,
                        resultsPerPage,
                        startIndex
                );
                logger.info("Calling Google Custom Search API with URL (key redacted, page {}):\n{}", i+1, maskedUrl);

                // Set up headers
                HttpHeaders headers = new HttpHeaders();
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
                HttpEntity<String> entity = new HttpEntity<>(headers);

                try {
                    // Make the API request
                    logger.info("Sending request to Google API (page {})...", i+1);
                    ResponseEntity<Map> response = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            Map.class
                    );

                    logger.info("Received response for page {} with status: {}", i+1, response.getStatusCode());
                    Map<String, Object> responseBody = response.getBody();
                    
                    // Add items to our collection
                    if (responseBody != null && responseBody.containsKey("items")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> items = (List<Map<String, Object>>) responseBody.get("items");
                        if (items != null && !items.isEmpty()) {
                            allItems.addAll(items);
                            logger.info("Added {} items from page {}", items.size(), i+1);
                            
                            // If we got fewer results than requested, we've reached the end
                            if (items.size() < resultsPerPage) {
                                logger.info("Reached end of results after {} pages", i+1);
                                break;
                            }
                        } else {
                            logger.info("No more items found after page {}", i+1);
                            break;
                        }
                    } else {
                        logger.warn("No items found in page {}", i+1);
                        break;
                    }
                    
                    // Add a small delay between requests to avoid rate limiting
                    try {
                        Thread.sleep(1000); // 1 second delay
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Thread interrupted during delay between API calls");
                    }
                    
                } catch (Exception e) {
                    logger.error("Error fetching page {}: {}", i+1, e.getMessage());
                    // Continue with the results we have so far
                    break;
                }
            }

            // Create a response body with all collected items
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("items", allItems);
            responseBody.put("searchInformation", Map.of("totalResults", String.valueOf(allItems.size())));
            logger.info("Collected a total of {} items from all pages", allItems.size());

            // Process the response
            if (responseBody.containsKey("items")) {
                // Extract company domain from the original query
                String companyDomain = extractCompanyDomain(q);
                if (companyDomain != null) {
                    // Add predicted emails to each result
                    List<Map<String, Object>> items = (List<Map<String, Object>>) responseBody.get("items");
                    for (Map<String, Object> item : items) {
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
                return ResponseEntity.ok(responseBody);
            } else {
                logger.warn("Received null response body from Google API");
            }

            return ResponseEntity.ok(responseBody);

        } catch (HttpClientErrorException e) {
            String errorMsg = String.format("Google API error: %s - %s", e.getStatusCode(), e.getStatusText());
            logger.error("{} - Response: {}", errorMsg, e.getResponseBodyAsString(), e);
            return ResponseEntity.status(e.getStatusCode())
                    .body(createErrorResponse(errorMsg));

        } catch (Exception e) {
            String errorMsg = "Failed to search for employees: " + e.getMessage();
            logger.error(errorMsg, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(errorMsg));
        }
    }

    private Map<String, String> createErrorResponse(String message) {
        Map<String, String> errorResponse = new HashMap<>();
        errorResponse.put("error", message);
        return errorResponse;
    }
    
    private String extractCompanyDomain(String query) {
        try {
            // First try to find a quoted company name
            String companyName = null;
            Pattern quotedPattern = Pattern.compile("AND\\s+['\"]([^'\"]+)['\"]");
            Matcher quotedMatcher = quotedPattern.matcher(query);
            
            if (quotedMatcher.find()) {
                companyName = quotedMatcher.group(1).trim();
            } else {
                // If no quoted name, try to extract the last word after AND
                String[] parts = query.split("AND");
                if (parts.length > 1) {
                    companyName = parts[parts.length - 1].trim();
                } else {
                    companyName = query.trim();
                }
            }
            
            if (companyName != null && !companyName.isEmpty()) {
                // Convert to lowercase and clean up the company name
                companyName = companyName.toLowerCase()
                    .replaceAll("^[^a-z0-9]+", "")  // Remove leading non-alphanumeric
                    .replaceAll("[^a-z0-9]+$", "")   // Remove trailing non-alphanumeric
                    .replaceAll("[^a-z0-9]+", "");    // Remove all other non-alphanumeric
                
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
            // Remove common LinkedIn title suffixes
            String cleanTitle = title.split("[&<>\"|\\n]")[0].trim();
            
            // Extract name from common LinkedIn title formats
            // Format 1: "First Last - Title at Company"
            // Format 2: "First Last on LinkedIn: [Title]"
            String[] parts = cleanTitle.split("[-|:]");
            if (parts.length > 0) {
                String namePart = parts[0].trim();
                // Remove common prefixes/suffixes
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
}