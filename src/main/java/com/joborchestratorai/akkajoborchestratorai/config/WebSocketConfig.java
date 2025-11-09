package com.joborchestratorai.akkajoborchestratorai.config;

import com.joborchestratorai.akkajoborchestratorai.websocket.ResumeOptimizationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private ResumeOptimizationHandler resumeOptimizationHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(resumeOptimizationHandler, "/ws/resume-optimize").setAllowedOrigins("*");
    }
}