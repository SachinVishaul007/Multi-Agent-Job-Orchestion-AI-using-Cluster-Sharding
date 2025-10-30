package com.joborchestratorai.akkajoborchestratorai.controllers;

import akka.actor.typed.ActorSystem;
import com.joborchestratorai.akkajoborchestratorai.services.ClusteredResumeSearchService;
import com.joborchestratorai.akkajoborchestratorai.services.EventSourcingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/health")
@Profile({"node1", "node2", "node3", "node4", "clustered"})
public class SimpleHealthController {

    private static final Logger logger = LoggerFactory.getLogger(SimpleHealthController.class);

    @Autowired
    private ActorSystem<?> actorSystem;
    
    @Autowired
    private ClusteredResumeSearchService clusteredService;
    
    @Autowired
    private EventSourcingService eventSourcingService;
    
    private final long startTime = System.currentTimeMillis();

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getHealthStatus() {
        try {
            Map<String, Object> health = new HashMap<>();
            
            // Basic application health
            health.put("status", "UP");
            health.put("timestamp", Instant.now().toString());
            health.put("uptime", System.currentTimeMillis() - startTime);
            
            // Actor system health
            Map<String, Object> actorSystemHealth = new HashMap<>();
            actorSystemHealth.put("name", actorSystem.name());
            actorSystemHealth.put("running", true);
            health.put("actorSystem", actorSystemHealth);
            
            // Cluster health (simplified)
            try {
                Map<String, Object> clusterHealth = new HashMap<>();
                clusterHealth.put("status", "UP");
                clusterHealth.put("mode", "clustered");
                health.put("cluster", clusterHealth);
            } catch (Exception e) {
                logger.warn("Failed to get cluster health: {}", e.getMessage());
                health.put("cluster", Map.of("status", "ERROR", "message", e.getMessage()));
            }
            
            // Service health checks
            Map<String, Object> services = new HashMap<>();
            services.put("clusteredSearchService", checkServiceHealth(clusteredService));
            services.put("eventSourcingService", checkServiceHealth(eventSourcingService));
            health.put("services", services);
            
            return ResponseEntity.ok(health);
            
        } catch (Exception e) {
            logger.error("Health check failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "status", "DOWN",
                "error", e.getMessage(),
                "timestamp", Instant.now().toString()
            ));
        }
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> getReadinessProbe() {
        try {
            Map<String, Object> readiness = new HashMap<>();
            boolean ready = true;
            List<String> checks = new ArrayList<>();
            
            // Basic readiness checks
            if (actorSystem == null) {
                ready = false;
                checks.add("ActorSystem not initialized");
            }
            
            if (clusteredService == null) {
                ready = false;
                checks.add("ClusteredResumeSearchService not available");
            }
            
            if (eventSourcingService == null) {
                ready = false;
                checks.add("EventSourcingService not available");
            }
            
            readiness.put("ready", ready);
            readiness.put("checks", checks);
            readiness.put("timestamp", Instant.now().toString());
            
            if (ready) {
                return ResponseEntity.ok(readiness);
            } else {
                return ResponseEntity.status(503).body(readiness);
            }
            
        } catch (Exception e) {
            logger.error("Readiness probe failed: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                "ready", false,
                "error", e.getMessage(),
                "timestamp", Instant.now().toString()
            ));
        }
    }

    @GetMapping("/liveness")
    public ResponseEntity<Map<String, Object>> getLivenessProbe() {
        try {
            Map<String, Object> liveness = new HashMap<>();
            boolean alive = true;
            
            // Basic liveness check - if we can respond, we're alive
            liveness.put("alive", alive);
            liveness.put("timestamp", Instant.now().toString());
            liveness.put("uptime", System.currentTimeMillis() - startTime);
            
            return ResponseEntity.ok(liveness);
            
        } catch (Exception e) {
            logger.error("Liveness probe failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "alive", false,
                "error", e.getMessage(),
                "timestamp", Instant.now().toString()
            ));
        }
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        try {
            Map<String, Object> metrics = new HashMap<>();
            
            // JVM metrics
            Runtime runtime = Runtime.getRuntime();
            Map<String, Object> jvm = new HashMap<>();
            jvm.put("totalMemory", runtime.totalMemory());
            jvm.put("freeMemory", runtime.freeMemory());
            jvm.put("usedMemory", runtime.totalMemory() - runtime.freeMemory());
            jvm.put("maxMemory", runtime.maxMemory());
            jvm.put("processors", runtime.availableProcessors());
            metrics.put("jvm", jvm);
            
            // Application metrics
            Map<String, Object> app = new HashMap<>();
            app.put("uptime", System.currentTimeMillis() - startTime);
            app.put("timestamp", Instant.now().toString());
            metrics.put("application", app);
            
            // Actor system metrics
            Map<String, Object> actorSystemMetrics = new HashMap<>();
            actorSystemMetrics.put("name", actorSystem.name());
            actorSystemMetrics.put("running", true);
            metrics.put("actorSystem", actorSystemMetrics);
            
            return ResponseEntity.ok(metrics);
            
        } catch (Exception e) {
            logger.error("Failed to get metrics: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to retrieve metrics: " + e.getMessage()
            ));
        }
    }

    private Map<String, Object> checkServiceHealth(Object service) {
        Map<String, Object> serviceHealth = new HashMap<>();
        try {
            if (service != null) {
                serviceHealth.put("status", "UP");
                serviceHealth.put("class", service.getClass().getSimpleName());
            } else {
                serviceHealth.put("status", "DOWN");
                serviceHealth.put("error", "Service is null");
            }
        } catch (Exception e) {
            serviceHealth.put("status", "ERROR");
            serviceHealth.put("error", e.getMessage());
        }
        return serviceHealth;
    }
}