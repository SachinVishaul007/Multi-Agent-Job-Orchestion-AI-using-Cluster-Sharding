package com.joborchestratorai.akkajoborchestratorai.websocket;

import com.joborchestratorai.akkajoborchestratorai.services.OpenAIService;
import com.joborchestratorai.akkajoborchestratorai.services.HybridLLMService;
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
    private HybridLLMService hybridLLMService;
    
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
                
                // Load master resume data from latest upload
                String masterResume = getMasterResumeData();
                if (masterResume == null || masterResume.trim().isEmpty()) {
                    session.sendMessage(new TextMessage("Error: Master resume data not found. Please upload your master resume first."));
                    return;
                }
                
                session.sendMessage(new TextMessage("Starting to analyze your resume and generate optimization suggestions..."));
                
                // Generate Basic optimization - What to Add/Remove
                session.sendMessage(new TextMessage("[BASIC-START]"));
                session.sendMessage(new TextMessage("[BASIC-TOKEN]🔄 Processing with local AI model (this may take 15-20 seconds)...\n\n"));
                StringBuilder basicContent = new StringBuilder();
                String basicPrompt = buildOptimizePrompt(baseResume, masterResume, jobDescription);
                
                // Use non-streaming call and send as complete response
                String result = hybridLLMService.processTextWithPrompt(basicPrompt);
                if (result != null && !result.trim().isEmpty()) {
                    basicContent.append(result);
                    session.sendMessage(new TextMessage("[BASIC-TOKEN]" + result));
                } else {
                    session.sendMessage(new TextMessage("[BASIC-TOKEN]No optimization suggestions generated."));
                }
                session.sendMessage(new TextMessage("[BASIC-COMPLETE]"));
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
    
    private String buildOptimizePrompt(String baseResume, String masterResume, String jd) {
        return String.format("""
            RESUME OPTIMIZATION ANALYSIS - Changes Needed
            
            Job Description:
            %s
            
            Current Base Resume:
            %s
            
            Master Resume Database (all experiences):
            %s
            
            TASK: Compare the base resume against the job requirements and provide clear, well-formatted recommendations.
            
            OUTPUT FORMAT (Use exactly this structure with proper spacing and formatting):
            
            ## 🔄 RECOMMENDED CHANGES
            
            ### ❌ REMOVE FROM BASE RESUME
            
            **Experience 1:**
            • [Brief summary of why removing]
            • Full bullet point text here
            
            **Experience 2:**
            • [Brief summary of why removing]
            • Full bullet point text here
            
            ---
            
            ### ✅ ADD FROM MASTER RESUME
            
            **Better Match 1:**
            • **Why relevant:** [Brief explanation of job match]
            • **Experience:** Full bullet point text from master resume (EXACT text)
            
            **Better Match 2:**
            • **Why relevant:** [Brief explanation of job match]  
            • **Experience:** Full bullet point text from master resume (EXACT text)
            
            ---
            
            ### 🎯 KEY IMPROVEMENTS
            
            **Critical Skills to Highlight:**
            • [Skill 1]: Present in master resume but underemphasized
            • [Skill 2]: Mentioned in job but missing from base
            
            **Strategic Recommendations:**
            • [Suggestion 1 for better positioning]
            • [Suggestion 2 for skill emphasis]
            
            RULES:
            - Use EXACT text from master resume (no modifications)
            - Keep explanations brief and actionable
            - Focus on 4-6 most impactful changes
            - Use proper markdown formatting with emojis and spacing
            """, jd, baseResume, masterResume);
    }

    // Advanced prompt moved to OpenAIService for Step 5 generation
    
    private String getMasterResumeData() {
        try {
            // Try to get from latest resume data (bullet points)
            var resumeData = localStorageService.getLatestResumeData();
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

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("Resume optimization WebSocket connection closed: " + session.getId());
    }
}