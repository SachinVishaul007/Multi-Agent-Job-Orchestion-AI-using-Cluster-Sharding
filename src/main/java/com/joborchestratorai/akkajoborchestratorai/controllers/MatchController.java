package com.joborchestratorai.akkajoborchestratorai.controllers;

import com.joborchestratorai.akkajoborchestratorai.models.ResumeData;
import com.joborchestratorai.akkajoborchestratorai.services.LocalStorageService;
import com.joborchestratorai.akkajoborchestratorai.services.OpenAIService;
import com.joborchestratorai.akkajoborchestratorai.services.HybridLLMService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin
@Profile("!clustered & !node1 & !node2 & !node3 & !node4")
public class MatchController {

    private final OpenAIService openAIService;
    private final HybridLLMService hybridLLMService;
    private final LocalStorageService localStorageService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public MatchController(OpenAIService openAIService, HybridLLMService hybridLLMService, LocalStorageService localStorageService) {
        this.openAIService = openAIService;
        this.hybridLLMService = hybridLLMService;
        this.localStorageService = localStorageService;
    }

    public static class MatchRequest {
        public String jobDescription;
        public List<String> tags;
    }

    public static class MatchResponse {
        public String matches; // raw text from the model

        public MatchResponse(String matches) {
            this.matches = matches;
        }
    }

    @PostMapping("/match-experiences")
    public ResponseEntity<?> matchExperiences(@RequestBody MatchRequest request) {
        try {
            if (request == null || request.jobDescription == null || request.jobDescription.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "jobDescription is required"));
            }

            List<String> tags = request.tags != null ? request.tags : List.of();

            // Load latest resume data (bullet points) from local storage
            ResumeData resumeData = localStorageService.getLatestResumeData();
            List<String> bulletPoints = resumeData != null && resumeData.getResumePoints() != null
                    ? resumeData.getResumePoints() : List.of();

            // Load detailed rows (bullet + detectedTags) and serialize as JSON
            java.util.List<com.joborchestratorai.akkajoborchestratorai.models.ResumeRow> rows = localStorageService.getLatestResumeRows();
            String rowsJson;
            try {
                rowsJson = objectMapper.writeValueAsString(rows);
            } catch (Exception ex) {
                rowsJson = "[]";
            }

            String prompt = buildPrompt(tags, request.jobDescription, bulletPoints, rowsJson);
            String result = hybridLLMService.processTextWithPrompt(prompt);
            return ResponseEntity.ok(new MatchResponse(result));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to load resume data: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String buildPrompt(List<String> tags, String jobDescription, List<String> bulletPoints, String rowsJson) {
        String tagsText = String.join(", ", tags);
        String excelData = String.join("\n", bulletPoints);
        return "## Task\n" +
                "Match job description requirements to my pre-written experiences using tags.\n\n" +
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

    public static class OptimizeRequest {
        public String jobDescription;
        public String pointsToAdd; // text block of bullets
    }

    public static class OptimizeResponse {
        public String basicChanges;
        public String advancedChanges;
        public OptimizeResponse(String basicChanges, String advancedChanges) {
            this.basicChanges = basicChanges;
            this.advancedChanges = advancedChanges;
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

            // Compute both variants eagerly
            String basicPrompt = buildOptimizePrompt(baseResume,
                    request.pointsToAdd == null ? "" : request.pointsToAdd,
                    request.jobDescription);
            String advancedPrompt = buildAdvancedOptimizePrompt(baseResume, request.jobDescription);

            String basic = hybridLLMService.processTextWithPrompt(basicPrompt);
            String advanced = hybridLLMService.processTextWithPrompt(advancedPrompt);
            return ResponseEntity.ok(new OptimizeResponse(basic, advanced));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to load base resume: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String buildOptimizePrompt(String baseResume, String pointsToAdd, String jd) {
        return "Task: Minimize changes to base resume while maximizing ATS score for the target JD. Show only essential modifications.\n" +
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
    // Advanced ATS prompt (JD + Base Resume)
    private String buildAdvancedOptimizePrompt(String baseResume, String jd) {
        return ("""
Advanced ATS Resume Tailoring Prompt
System Instructions
You are an expert ATS optimization specialist and career coach. Your task is to analyze a resume against a specific job description and provide strategic, line-by-line recommendations that maximize ATS scoring while maintaining authenticity and professional credibility.

Input Requirements
Input 1: Job Description
%s
Input 2: Current Resume
%s

Analysis Framework
Phase 1: Keyword Extraction & Mapping

Extract Critical Keywords from Job Description:

Technical skills (programming languages, tools, frameworks)
Soft skills (leadership, communication, analytical)
Industry-specific terminology
Action verbs used by the employer
Certifications and qualifications
Years of experience requirements


Create Keyword Frequency Map:

Primary keywords (mentioned 3+ times)
Secondary keywords (mentioned 1-2 times)
Implicit keywords (industry-standard terms related to mentioned skills)



Phase 2: Line-by-Line Optimization
For each line in the resume, provide:
ORIGINAL LINE: [Current text]

OPTIMIZED VERSION: [Enhanced text with keywords]

CHANGES MADE:
- Replaced "[original word]" with "[keyword]" (Reason: Direct match with JD requirement)
- Added metric "[specific number/percentage]" (Reason: Quantifies impact)
- Incorporated action verb "[verb]" (Reason: Mirrors JD language)

ATS IMPACT SCORE: [1-10]
AUTHENTICITY CHECK: [Natural/Slightly Modified/Needs Review]
Phase 3: Strategic Enhancement Rules
Apply these principles for natural integration:

Contextual Synonym Replacement

Use keywords as natural synonyms, not forced insertions
Example: "created" → "developed" if JD emphasizes development


Metric Integration

Add quantifiable results where missing
Use ranges if exact numbers unknown (e.g., "15-20%% improvement")


Technical Stack Alignment

Mirror the exact version/format mentioned in JD
Example: If JD says "React.js", don't write "React"


Power Verb Optimization

Match the intensity level of JD action verbs
If JD uses "spearheaded," elevate "led" to "spearheaded" where truthful



Phase 4: Skill Gap Analysis
CRITICAL GAPS (Must Address):
- [Skill]: Impact on application [High/Medium/Low]
  Recommendation: [Learning path/Alternative positioning]

NICE-TO-HAVE GAPS:
- [Skill]: Could strengthen application
  Quick Win Strategy: [30-day action plan]

TRANSFERABLE SKILLS TO EMPHASIZE:
- Your [existing skill] translates to their [required skill]
  Bridge Statement: [How to connect these in resume]
Phase 5: Differentiation Strategy
Stand-Out Tactics Beyond Keywords:

Unique Value Proposition Section
Create a 2-3 line summary that addresses their EXACT pain points with your UNIQUE solution approach
Problem-Solution-Result Framework
Structure achievements as: Challenge faced → Your approach → Measurable outcome
Industry-Specific Accomplishments
Highlight achievements using their industry's success metrics
Cultural Fit Indicators
Subtly incorporate company values through achievement descriptions
Technical Depth Demonstration
Include one highly specific technical achievement that shows expertise beyond surface level

Phase 6: ATS Scoring Prediction
ESTIMATED ATS MATCH SCORE: [X]%%

Breakdown:
- Hard Skills Match: [X/Y keywords matched]
- Soft Skills Match: [X/Y keywords matched]
- Experience Alignment: [X years matched of Y required]
- Education/Certification Match: [Met/Partially Met/Gap]

HUMAN REVIEWER APPEAL SCORE: [1-10]
- Readability: [Score]
- Authenticity: [Score]
- Impact Clarity: [Score]
Phase 7: Implementation Checklist

 All primary keywords appear 2-3 times naturally
 Secondary keywords appear at least once
 Every bullet point starts with a strong action verb
 70%% of achievements include quantifiable metrics
 Technical terms match JD formatting exactly
 No keyword stuffing or unnatural repetition
 Maintained truthfulness in all modifications
 Added context-specific achievements relevant to role
 Addressed top 3 requirements in first 1/3 of resume
 File saved in ATS-friendly format (.docx or .pdf with selectable text)

Output Format
Section 1: Quick Wins (Implement Immediately)
[5-7 highest-impact changes that take <10 minutes]
Section 2: Line-by-Line Optimizations
[Complete detailed analysis as specified above]
Section 3: Skill Development Roadmap
[Prioritized learning path for gaps]
Section 4: Competitive Edge Strategy
[3 unique elements that differentiate from other applicants]
Section 5: Final ATS-Optimized Resume
[Complete rewritten version]

Advanced Differentiation Strategies
To stand out among AI-tailored resumes:

The "Proof Layer"

Add LinkedIn article links, GitHub repos, or portfolio items that validate claims
Include a "Featured Project" that directly solves a problem mentioned in JD


The "Network Signal"

Mention mutual connections or company-specific knowledge
Reference recent company news/initiatives in cover letter


The "Technical Challenge Response"

Create a brief case study addressing their specific technical stack
Include a "How I Would Approach This Role" section in cover letter


The "Cultural Alignment Story"

Weave company values into achievement stories
Use their terminology for team structures and methodologies


The "Future Value Proposition"

Include a "90-Day Impact Plan" showing immediate value
Demonstrate knowledge of their industry challenges and your solutions



Warning Flags to Avoid

Over-optimization (using a keyword more than 4 times)
Exact copying of JD phrases (rephrase in your voice)
Adding skills you cannot defend in an interview
Sacrificing readability for keyword density
Using outdated versions of technologies mentioned
Generic achievements without specific context
Misaligned experience levels (don't overstate seniority)


Remember: The goal is to pass ATS AND impress human reviewers. Every optimization should enhance both machine readability and human engagement.
""").formatted(jd, baseResume);
    }
}
