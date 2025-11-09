package com.joborchestratorai.akkajoborchestratorai.websocket;

import com.joborchestratorai.akkajoborchestratorai.services.OpenAIService;
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

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("Resume optimization WebSocket connection established: " + session.getId());
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String resumeText = message.getPayload();
        System.out.println("Received resume text for optimization: " + resumeText.substring(0, Math.min(100, resumeText.length())) + "...");
        
        CompletableFuture.runAsync(() -> {
            try {
                String prompt = "Please analyze the following resume and provide specific suggestions for improvement. Focus on:\n" +
                               "1. Content improvements (skills, experience, achievements)\n" +
                               "2. Formatting and structure\n" +
                               "3. Industry-specific recommendations\n" +
                               "4. ATS optimization\n\n" +
                               "Resume:\n" + resumeText;
                
                session.sendMessage(new TextMessage("Starting to analyze your resume and generate optimization suggestions..."));
                
                openAIService.callOpenAIStream(prompt, chunk -> {
                    try {
                        session.sendMessage(new TextMessage(chunk));
                    } catch (Exception e) {
                        System.err.println("Error sending message: " + e.getMessage());
                    }
                });
                
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

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("Resume optimization WebSocket connection closed: " + session.getId());
    }
}