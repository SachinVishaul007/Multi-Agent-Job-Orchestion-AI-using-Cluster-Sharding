package com.joborchestratorai.akkajoborchestratorai.websocket;

import com.joborchestratorai.akkajoborchestratorai.services.OpenAIService;
import com.joborchestratorai.akkajoborchestratorai.services.LocalStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.CompletableFuture;

@Component
public class ResumeOptimizationHandler extends TextWebSocketHandler {

    @Autowired
    private OpenAIService openAIService;
    
    @Autowired
    private LocalStorageService localStorageService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("Resume optimization WebSocket connection established: " + session.getId());
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String jobDescription = message.getPayload();
        System.out.println("Received job description for optimization: " + jobDescription.substring(0, Math.min(100, jobDescription.length())) + "...");
        
        CompletableFuture.runAsync(() -> {
            try {
                String baseResume = localStorageService.getBaseResumeText();
                if (baseResume == null || baseResume.trim().isEmpty()) {
                    session.sendMessage(new TextMessage("Error: Base resume not uploaded yet"));
                    return;
                }
                
                session.sendMessage(new TextMessage("Starting to analyze your resume and generate optimization suggestions..."));
                
                // Generate Basic optimization first
                session.sendMessage(new TextMessage("[BASIC-START]"));
                StringBuilder basicContent = new StringBuilder();
                String basicPrompt = buildOptimizePrompt(baseResume, "", jobDescription);
                
                openAIService.callOpenAIStream(basicPrompt, chunk -> {
                    try {
                        basicContent.append(chunk);
                        session.sendMessage(new TextMessage("[BASIC-TOKEN]" + chunk));
                    } catch (Exception e) {
                        System.err.println("Error sending basic message: " + e.getMessage());
                    }
                });
                session.sendMessage(new TextMessage("[BASIC-COMPLETE]"));
                
                // Generate Advanced optimization
                session.sendMessage(new TextMessage("[ADVANCED-START]"));
                StringBuilder advancedContent = new StringBuilder();
                String advancedPrompt = buildAdvancedOptimizePrompt(baseResume, jobDescription);
                
                openAIService.callOpenAIStream(advancedPrompt, chunk -> {
                    try {
                        advancedContent.append(chunk);
                        session.sendMessage(new TextMessage("[ADVANCED-TOKEN]" + chunk));
                    } catch (Exception e) {
                        System.err.println("Error sending advanced message: " + e.getMessage());
                    }
                });
                session.sendMessage(new TextMessage("[ADVANCED-COMPLETE]"));
                
                session.sendMessage(new TextMessage("[DONE]"));
            } catch (Exception e) {
                try {
                    session.sendMessage(new TextMessage("Error: " + e.getMessage()));
                } catch (Exception ex) {
                    System.err.println("Error sending error message: " + ex.getMessage());
                }
            }
        });
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
            "- Focus on measurable impact",
            jd, baseResume
        );
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("Resume optimization WebSocket connection closed: " + session.getId());
    }
}