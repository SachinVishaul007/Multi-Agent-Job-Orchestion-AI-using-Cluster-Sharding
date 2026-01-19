package com.joborchestratorai.akkajoborchestratorai.controllers;

import akka.actor.typed.ActorSystem;
import com.joborchestratorai.akkajoborchestratorai.actors.ClusteredMasterActor;
import com.joborchestratorai.akkajoborchestratorai.models.SearchResult;
import com.joborchestratorai.akkajoborchestratorai.services.ClusteredResumeSearchService;
import com.joborchestratorai.akkajoborchestratorai.services.LocalStorageService;
import com.joborchestratorai.akkajoborchestratorai.services.OpenAIService;
import com.joborchestratorai.akkajoborchestratorai.services.EventSourcingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
@Profile({"node1", "node2", "node3", "node4", "clustered"})
@CrossOrigin
public class ClusteredMatchController {

    @Autowired
    private ClusteredResumeSearchService clusteredService;
    
    @Autowired
    private OpenAIService openAIService;
    
    @Autowired
    private LocalStorageService localStorageService;
    
    @Autowired
    private EventSourcingService eventSourcingService;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public static class MatchRequest {
        public String jobDescription;
        public List<String> tags;
    }

    public static class MatchResponse {
        public String matches;
        public String clusterInfo;
        public boolean distributedSearch;

        public MatchResponse(String matches, String clusterInfo, boolean distributedSearch) {
            this.matches = matches;
            this.clusterInfo = clusterInfo;
            this.distributedSearch = distributedSearch;
        }
    }

    @PostMapping("/match-experiences")
    public ResponseEntity<?> matchExperiences(@RequestBody MatchRequest request) {
        try {
            if (request == null || request.jobDescription == null || request.jobDescription.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "jobDescription is required"));
            }

            List<String> tags = request.tags != null ? request.tags : List.of();

            // Extract company domain for targeted shard search
            String companyDomain = extractCompanyFromJobDescription(request.jobDescription);
            String datasetId = companyDomain + "-resumes";

            // Try distributed search across multiple datasets first
            List<String> availableDatasets = clusteredService.getAllDatasetIds();
            List<String> targetDatasets = availableDatasets.isEmpty() ? 
                List.of(datasetId) : availableDatasets;

            CompletableFuture<List<SearchResult>> distributedSearch = null;
            
            try {
                // Attempt distributed search across cluster
                distributedSearch = clusteredService.searchAcrossDatasets(
                    targetDatasets, 
                    request.jobDescription, 
                    10
                ).orTimeout(45, TimeUnit.SECONDS);

                List<SearchResult> clusterResults = distributedSearch.get(45, TimeUnit.SECONDS);
                
                if (!clusterResults.isEmpty()) {
                    // Convert search results to bullet text format
                    StringBuilder matchesText = new StringBuilder();
                    for (SearchResult result : clusterResults) {
                        matchesText.append(result.getContent()).append("\n");
                    }

                    String clusterInfo = String.format("Searched across %d datasets on cluster, found %d matches", 
                        targetDatasets.size(), clusterResults.size());

                    return ResponseEntity.ok(new MatchResponse(
                        matchesText.toString().trim(), 
                        clusterInfo, 
                        true
                    ));
                }
            } catch (Exception clusterEx) {
                System.err.println("Cluster search failed: " + clusterEx.getMessage());
                // Fall back to local processing
            }

            // Fallback to local processing with existing logic
            try {
                com.joborchestratorai.akkajoborchestratorai.models.ResumeData resumeData = localStorageService.getLatestResumeData();
                List<String> bulletPoints = resumeData != null && resumeData.getResumePoints() != null
                        ? resumeData.getResumePoints() : List.of();

                List<com.joborchestratorai.akkajoborchestratorai.models.ResumeRow> rows = localStorageService.getLatestResumeRows();
                String rowsJson;
                try {
                    rowsJson = objectMapper.writeValueAsString(rows);
                } catch (Exception ex) {
                    rowsJson = "[]";
                }

                String prompt = buildPrompt(tags, request.jobDescription, bulletPoints, rowsJson);
                String result = openAIService.processTextWithPrompt(prompt);

                return ResponseEntity.ok(new MatchResponse(
                    result, 
                    "Processed locally (cluster unavailable)", 
                    false
                ));

            } catch (Exception localEx) {
                System.err.println("Local processing also failed: " + localEx.getMessage());
                return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Both distributed and local search failed: " + localEx.getMessage()
                ));
            }

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    public static class OptimizeRequest {
        public String jobDescription;
        public String pointsToAdd;
    }

    public static class OptimizeResponse {
        public String basicChanges;
        public String advancedChanges;
        public boolean distributedProcessing;

        public OptimizeResponse(String basicChanges, String advancedChanges, boolean distributedProcessing) {
            this.basicChanges = basicChanges;
            this.advancedChanges = advancedChanges;
            this.distributedProcessing = distributedProcessing;
        }
    }

    @PostMapping("/optimize-resume")
    public ResponseEntity<?> optimizeResume(@RequestBody OptimizeRequest request) {
        try {
            String baseResume = localStorageService.getBaseResumeText();
            if (baseResume == null || baseResume.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Base resume not uploaded yet"));
            }
            if (request == null || request.jobDescription == null || request.jobDescription.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "jobDescription is required"));
            }

            // Try distributed optimization processing
            try {
                // Use cluster for parallel basic/advanced processing
                CompletableFuture<String> basicFuture = CompletableFuture.supplyAsync(() -> {
                    String basicPrompt = buildOptimizePrompt(baseResume,
                            request.pointsToAdd == null ? "" : request.pointsToAdd,
                            request.jobDescription);
                    return openAIService.processTextWithPrompt(basicPrompt);
                });

                CompletableFuture<String> advancedFuture = CompletableFuture.supplyAsync(() -> {
                    String advancedPrompt = buildAdvancedOptimizePrompt(baseResume, request.jobDescription);
                    return openAIService.processTextWithPrompt(advancedPrompt);
                });

                // Wait for both to complete with timeout
                CompletableFuture.allOf(basicFuture, advancedFuture).get(60, TimeUnit.SECONDS);

                String basic = basicFuture.get();
                String advanced = advancedFuture.get();

                return ResponseEntity.ok(new OptimizeResponse(basic, advanced, true));

            } catch (Exception e) {
                System.err.println("Distributed optimization failed: " + e.getMessage());
                
                // Fallback to sequential processing
                String basicPrompt = buildOptimizePrompt(baseResume,
                        request.pointsToAdd == null ? "" : request.pointsToAdd,
                        request.jobDescription);
                String advancedPrompt = buildAdvancedOptimizePrompt(baseResume, request.jobDescription);

                String basic = openAIService.processTextWithPrompt(basicPrompt);
                String advanced = openAIService.processTextWithPrompt(advancedPrompt);

                return ResponseEntity.ok(new OptimizeResponse(basic, advanced, false));
            }

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String extractCompanyFromJobDescription(String jobDescription) {
        // Simple company extraction logic - look for common patterns
        String[] lines = jobDescription.split("\n");
        for (String line : lines) {
            line = line.trim().toLowerCase();
            if (line.contains("company:") || line.contains("employer:")) {
                String[] parts = line.split(":");
                if (parts.length > 1) {
                    return cleanCompanyName(parts[1].trim());
                }
            }
            // Look for "at [Company]" patterns
            if (line.contains(" at ")) {
                String[] parts = line.split(" at ");
                if (parts.length > 1) {
                    return cleanCompanyName(parts[1].trim());
                }
            }
        }
        return "default";
    }

    private String cleanCompanyName(String company) {
        return company.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .substring(0, Math.min(company.length(), 20));
    }

    private String buildPrompt(List<String> tags, String jobDescription, List<String> bulletPoints, String rowsJson) {
        String tagsText = String.join(", ", tags);
        String excelData = String.join("\n", bulletPoints);
        return "## Task\n" +
                "Match job description requirements to my pre-written experiences using tags (DISTRIBUTED CLUSTER PROCESSING).\n\n" +
                "## Inputs\n" +
                "**Tags:** " + tagsText + "\n" +
                "**Job Description:** " + jobDescription + "\n" +
                "**Experience Database (flat bullets):** " + excelData + "\n" +
                "**Experience Table (JSON):** " + rowsJson + "\n\n" +
                "## Instructions\n" +
                "1. Read the JD and identify relevant tags from the provided tag list for each requirement\n" +
                "2. Use the JSON table to match bullets whose detectedTags intersect with the tags you identify\n" +
                "3. Return ONLY the matching \"Bullet Text\" entries - EXACTLY as written, no modifications\n\n" +
                "## Output Rules\n" +
                "- Copy-paste the exact text from \"Bullet Text\" column\n" +
                "- Include only experiences with at least 1 matching tag\n" +
                "- No explanations, no headers, just the bullet points\n" +
                "- Order by relevance (most tag matches first)\n\n" +
                "**Remember: Return ONLY the exact bullet text. Make ZERO changes.**";
    }

    private String buildOptimizePrompt(String baseResume, String pointsToAdd, String jd) {
        return "Task: Minimize changes to base resume while maximizing ATS score for the target JD (CLUSTER PROCESSED). Show only essential modifications.\n" +
                "Inputs:\n\n" +
                "Base Resume: " + baseResume + "\n\n" +
                "Points to add (skip if exists already in base resume): " + pointsToAdd + "\n\n" +
                "Target JD: " + jd + "\n\n" +
                "First, identify:\n\n" +
                "Company type (startup/enterprise) & role level (junior/senior/lead)\n" +
                "Top 10 ATS keywords from JD missing in base resume\n\n" +
                "Output Format - CHANGES ONLY:\n" +
                "Summary Section\n[original phrase] → [new phrase with keywords]\n" +
                "Skills\nAdd: [missing critical skills from JD]\n" +
                "Experience Bullets (only if keyword-critical)\n[Company, Role]\n\n[original bullet] → [bullet with naturally integrated JD keywords]\n\n" +
                "Quick Adds\n\n\n" +
                "Rules:\n\n" +
                "DON'T change: numbers, metrics, actual achievements\n" +
                "ONLY change: generic terms → JD-specific keywords\n" +
                "Keep all changes truthful\n" +
                "Max 5-7 total modifications\n\n" +
                "Impact: Current ATS match: X% → After changes: Y%";
    }

    private String buildAdvancedOptimizePrompt(String baseResume, String jd) {
        return String.format(
            "Advanced ATS Resume Optimization (CLUSTER PROCESSED)\n\n" +
            "Job Description:\n%s\n\n" +
            "Current Resume:\n%s\n\n" +
            "TASK: Provide strategic ATS optimization with line-by-line recommendations.\n\n" +
            "ANALYSIS REQUIRED:\n" +
            "1. Extract primary keywords (3+ mentions) and secondary keywords (1-2 mentions)\n" +
            "2. For each resume section, provide:\n" +
            "   - ORIGINAL: [current text]\n" +
            "   - OPTIMIZED: [enhanced with keywords]\n" +
            "   - IMPACT: [ATS score 1-10]\n\n" +
            "3. Skill Gap Analysis:\n" +
            "   - Critical gaps requiring immediate attention\n" +
            "   - Transferable skills to emphasize\n\n" +
            "4. ATS Score Prediction:\n" +
            "   - Hard/Soft skills match\n" +
            "   - Experience alignment\n" +
            "   - Overall percentage\n\n" +
            "OUTPUT FORMAT:\n" +
            "Quick Wins (5-7 immediate changes)\n" +
            "Line-by-Line Optimizations\n" +
            "Skill Development Roadmap\n" +
            "Final Optimized Resume\n\n" +
            "RULES:\n" +
            "- Natural keyword integration (no stuffing)\n" +
            "- Maintain truthfulness\n" +
            "- Add metrics where missing\n" +
            "- Match JD technical formatting exactly\n" +
            "- Keep human readability high",
            jd, baseResume
        );
    }

    @PostMapping("/match-experiences-stream")
    public SseEmitter matchExperiencesStream(@RequestBody MatchRequest request) {
        SseEmitter emitter = new SseEmitter(60000L); // 60 second timeout
        
        CompletableFuture.runAsync(() -> {
            try {
                if (request == null || request.jobDescription == null || request.jobDescription.trim().isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("jobDescription is required"));
                    emitter.complete();
                    return;
                }

                List<String> tags = request.tags != null ? request.tags : List.of();
                String companyDomain = extractCompanyFromJobDescription(request.jobDescription);
                String datasetId = companyDomain + "-resumes";

                // Try distributed search first
                List<String> availableDatasets = clusteredService.getAllDatasetIds();
                List<String> targetDatasets = availableDatasets.isEmpty() ? 
                    List.of(datasetId) : availableDatasets;

                StringBuilder matchesText = new StringBuilder();
                
                try {
                    CompletableFuture<List<SearchResult>> distributedSearch = clusteredService.searchAcrossDatasets(
                        targetDatasets, request.jobDescription, 10
                    ).orTimeout(30, TimeUnit.SECONDS);

                    List<SearchResult> clusterResults = distributedSearch.get(30, TimeUnit.SECONDS);
                    
                    if (!clusterResults.isEmpty()) {
                        for (SearchResult result : clusterResults) {
                            matchesText.append(result.getContent()).append("\n");
                        }
                    }
                } catch (Exception clusterEx) {
                    System.err.println("Cluster search failed, falling back to local: " + clusterEx.getMessage());
                    
                    // Fallback to local processing
                    com.joborchestratorai.akkajoborchestratorai.models.ResumeData resumeData = localStorageService.getLatestResumeData();
                    List<String> bulletPoints = resumeData != null && resumeData.getResumePoints() != null
                            ? resumeData.getResumePoints() : List.of();
                    
                    for (String bullet : bulletPoints) {
                        matchesText.append(bullet).append("\n");
                    }
                }

                // Send the complete result as one event for now
                // In a real implementation, you'd want to stream individual matches as they're found
                emitter.send(SseEmitter.event().name("matches").data(matchesText.toString().trim()));
                emitter.send(SseEmitter.event().name("complete").data(""));
                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("Error: " + e.getMessage()));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    @GetMapping("/optimize-resume-stream")
    public SseEmitter optimizeResumeStream(@RequestParam String jobDescription, 
                                          @RequestParam(required = false, defaultValue = "") String pointsToAdd) {
        OptimizeRequest request = new OptimizeRequest();
        request.jobDescription = jobDescription;
        request.pointsToAdd = pointsToAdd;
        SseEmitter emitter = new SseEmitter(120000L); // 2 minute timeout
        
        CompletableFuture.runAsync(() -> {
            try {
                String baseResume = localStorageService.getBaseResumeText();
                if (baseResume == null || baseResume.trim().isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("Base resume not uploaded yet"));
                    emitter.complete();
                    return;
                }
                if (request == null || request.jobDescription == null || request.jobDescription.trim().isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("jobDescription is required"));
                    emitter.complete();
                    return;
                }

                // Stream basic optimization
                emitter.send(SseEmitter.event().name("basic-start").data(""));
                StringBuilder basicResult = new StringBuilder();
                String basicPrompt = buildOptimizePrompt(baseResume,
                        request.pointsToAdd == null ? "" : request.pointsToAdd,
                        request.jobDescription);
                
                openAIService.callOpenAIStream("", basicPrompt, 0.3, 4000, token -> {
                    try {
                        basicResult.append(token);
                        emitter.send(SseEmitter.event().name("basic-token").data(token));
                    } catch (Exception e) {
                        System.err.println("Error sending basic token: " + e.getMessage());
                    }
                });
                emitter.send(SseEmitter.event().name("basic-complete").data(""));

                // Stream advanced optimization
                emitter.send(SseEmitter.event().name("advanced-start").data(""));
                StringBuilder advancedResult = new StringBuilder();
                String advancedPrompt = buildAdvancedOptimizePrompt(baseResume, request.jobDescription);
                
                openAIService.callOpenAIStream("", advancedPrompt, 0.3, 4000, token -> {
                    try {
                        advancedResult.append(token);
                        emitter.send(SseEmitter.event().name("advanced-token").data(token));
                    } catch (Exception e) {
                        System.err.println("Error sending advanced token: " + e.getMessage());
                    }
                });
                emitter.send(SseEmitter.event().name("advanced-complete").data(""));
                emitter.send(SseEmitter.event().name("complete").data(""));
                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("Error: " + e.getMessage()));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    @PostMapping("/generate-resume")
    public ResponseEntity<Map<String, String>> generateTailoredResume(@RequestBody Map<String, String> request) {
        try {
            String jobDescription = request.get("jobDescription");

            if (jobDescription == null || jobDescription.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Job description is required", "status", "error"));
            }

            // Get base resume text
            String baseResume = localStorageService.getBaseResumeText();
            if (baseResume == null || baseResume.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Base resume not uploaded yet. Please upload a base resume PDF first.", "status", "error"));
            }

            // Get master resume data (bullet points)
            String masterResume = getMasterResumeData();
            if (masterResume == null || masterResume.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Master resume data not found. Please upload your master resume first.", "status", "error"));
            }

            String prompt = buildAdvancedGeneratePrompt(baseResume, masterResume, jobDescription);
            String latexResume = openAIService.processTextWithPrompt(prompt);

            return ResponseEntity.ok(Map.of(
                    "message", "Resume generated successfully using local AI",
                    "latexCode", latexResume,
                    "status", "success"
            ));

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Failed to generate resume: " + e.getMessage(),
                            "status", "error"
                    ));
        }
    }

    private String getMasterResumeData() {
        try {
            // Try to get from latest resume data (bullet points)
            com.joborchestratorai.akkajoborchestratorai.models.ResumeData resumeData = localStorageService.getLatestResumeData();
            if (resumeData != null && resumeData.getResumePoints() != null && !resumeData.getResumePoints().isEmpty()) {
                return String.join("\n", resumeData.getResumePoints());
            }
            
            // Fallback to base resume if master data not available
            String baseText = localStorageService.getBaseResumeText();
            return baseText != null ? baseText : "";
        } catch (Exception e) {
            System.err.println("Error loading master resume data: " + e.getMessage());
            return "";
        }
    }

    private String buildAdvancedGeneratePrompt(String baseResume, String masterResume, String jd) {
        return String.format("""
            ADVANCED RESUME OPTIMIZATION - Complete Optimized LaTeX Resume (CLUSTER PROCESSED)
            
            Job Description:
            %s
            
            Base Resume Template:
            %s
            
            Master Resume Database (all experiences):
            %s
            
            TASK: Generate a complete, optimized LaTeX resume ready to compile.
            
            REQUIREMENTS:
            1. Use the base resume structure and formatting
            2. Replace less relevant experiences with better matches from master resume
            3. Use EXACT text from master resume (no modifications)
            4. Maintain all personal information, education, dates, company names
            5. Ensure resume fits on ONE page
            6. Optimize for job requirements while staying truthful
            
            OUTPUT: Return ONLY the complete LaTeX code, ready to compile.
            Do not include explanations or comments - just the compilable LaTeX resume.
            
            The resume should:
            - Keep the same professional structure
            - Prioritize most relevant experiences from master database
            - Include job-relevant skills and technologies
            - Maintain consistent formatting throughout
            """, jd, baseResume, masterResume);
    }
}