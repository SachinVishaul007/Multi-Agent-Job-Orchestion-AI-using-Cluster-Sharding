package com.joborchestratorai.akkajoborchestratorai.services;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import akka.util.Timeout;
import com.joborchestratorai.akkajoborchestratorai.actors.ResumeProcessorActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@Profile({"node1", "node2", "node3", "node4", "clustered"})
public class EventSourcingService {

    private static final Logger logger = LoggerFactory.getLogger(EventSourcingService.class);
    
    @Autowired
    private ActorSystem<?> actorSystem;
    
    // Cache for actor references to avoid creating multiple actors for the same resume
    private final ConcurrentHashMap<String, ActorRef<ResumeProcessorActor.Command>> resumeActors = new ConcurrentHashMap<>();
    
    private final Timeout timeout = Timeout.create(Duration.ofSeconds(30));

    /**
     * Process a resume using event sourcing and persist all events
     */
    public CompletableFuture<ResumeProcessingResult> processResumeWithEventSourcing(String resumeId, String content, String companyDomain) {
        try {
            logger.info("Starting event-sourced processing for resume: {} with company domain: {}", resumeId, companyDomain);
            
            // Create or get existing actor for this resume
            ActorRef<ResumeProcessorActor.Command> resumeActor = getOrCreateResumeActor(resumeId, companyDomain);
            
            // Send process command to the actor
            CompletableFuture<ResumeProcessorActor.OperationResult> akkaFuture = AskPattern.<ResumeProcessorActor.Command, ResumeProcessorActor.OperationResult>ask(
                resumeActor,
                replyTo -> new ResumeProcessorActor.ProcessResume(resumeId, content, replyTo),
                Duration.ofSeconds(30),
                actorSystem.scheduler()
            ).toCompletableFuture();
            
            // Convert Akka Future to CompletableFuture and transform result
            return akkaFuture
                .thenApply(result -> {
                    logger.info("Resume processing completed for {}: success={}, message={}", 
                        result.resumeId, result.success, result.message);
                    
                    return new ResumeProcessingResult(
                        result.resumeId,
                        result.success,
                        result.message,
                        companyDomain,
                        System.currentTimeMillis()
                    );
                })
                .exceptionally(throwable -> {
                    logger.error("Resume processing failed for {}: {}", resumeId, throwable.getMessage());
                    return new ResumeProcessingResult(
                        resumeId,
                        false,
                        "Processing failed: " + throwable.getMessage(),
                        companyDomain,
                        System.currentTimeMillis()
                    );
                });
                
        } catch (Exception e) {
            logger.error("Error starting resume processing for {}: {}", resumeId, e.getMessage());
            CompletableFuture<ResumeProcessingResult> failedFuture = new CompletableFuture<>();
            failedFuture.complete(new ResumeProcessingResult(
                resumeId,
                false,
                "Failed to start processing: " + e.getMessage(),
                companyDomain,
                System.currentTimeMillis()
            ));
            return failedFuture;
        }
    }
    
    /**
     * Get the processing status and event history for a resume
     */
    public CompletableFuture<ResumeProcessorActor.StatusResponse> getResumeStatus(String resumeId, String companyDomain) {
        try {
            logger.info("Getting status for resume: {} with company domain: {}", resumeId, companyDomain);
            
            // Get the actor for this resume
            ActorRef<ResumeProcessorActor.Command> resumeActor = getOrCreateResumeActor(resumeId, companyDomain);
            
            // Ask for status
            CompletableFuture<ResumeProcessorActor.StatusResponse> akkaFuture = AskPattern.<ResumeProcessorActor.Command, ResumeProcessorActor.StatusResponse>ask(
                resumeActor,
                replyTo -> new ResumeProcessorActor.GetStatus(resumeId, replyTo),
                Duration.ofSeconds(30),
                actorSystem.scheduler()
            ).toCompletableFuture();
            
            return akkaFuture
                .thenApply(status -> {
                    logger.info("Retrieved status for resume {}: status={}, history_count={}", 
                        status.resumeId, status.status, status.history.size());
                    return status;
                })
                .exceptionally(throwable -> {
                    logger.error("Error getting status for resume {}: {}", resumeId, throwable.getMessage());
                    return new ResumeProcessorActor.StatusResponse(
                        resumeId,
                        "ERROR: " + throwable.getMessage(),
                        java.util.Collections.emptyList()
                    );
                });
                
        } catch (Exception e) {
            logger.error("Error starting status query for {}: {}", resumeId, e.getMessage());
            CompletableFuture<ResumeProcessorActor.StatusResponse> failedFuture = new CompletableFuture<>();
            failedFuture.complete(new ResumeProcessorActor.StatusResponse(
                resumeId,
                "ERROR: " + e.getMessage(),
                java.util.Collections.emptyList()
            ));
            return failedFuture;
        }
    }
    
    /**
     * Process a job matching request with event sourcing
     */
    public CompletableFuture<JobMatchingResult> processJobMatchingWithEventSourcing(
            String jobId, String jobDescription, String resumeContent, String companyDomain) {
        
        try {
            logger.info("Starting event-sourced job matching for job: {} with company: {}", jobId, companyDomain);
            
            // Create a combined resume ID for job matching
            String resumeId = jobId + "-matching-" + System.currentTimeMillis();
            
            // First process the resume for this job matching
            return processResumeWithEventSourcing(resumeId, resumeContent, companyDomain)
                .thenApply(processingResult -> {
                    logger.info("Job matching processing completed for job {}: success={}", 
                        jobId, processingResult.success);
                    
                    return new JobMatchingResult(
                        jobId,
                        resumeId,
                        processingResult.success,
                        processingResult.message,
                        companyDomain,
                        jobDescription,
                        System.currentTimeMillis()
                    );
                })
                .exceptionally(throwable -> {
                    logger.error("Job matching failed for job {}: {}", jobId, throwable.getMessage());
                    return new JobMatchingResult(
                        jobId,
                        resumeId,
                        false,
                        "Job matching failed: " + throwable.getMessage(),
                        companyDomain,
                        jobDescription,
                        System.currentTimeMillis()
                    );
                });
                
        } catch (Exception e) {
            logger.error("Error starting job matching for {}: {}", jobId, e.getMessage());
            CompletableFuture<JobMatchingResult> failedFuture = new CompletableFuture<>();
            failedFuture.complete(new JobMatchingResult(
                jobId,
                "unknown",
                false,
                "Failed to start job matching: " + e.getMessage(),
                companyDomain,
                jobDescription,
                System.currentTimeMillis()
            ));
            return failedFuture;
        }
    }
    
    /**
     * Get or create a resume processor actor with company-based persistence ID
     */
    private ActorRef<ResumeProcessorActor.Command> getOrCreateResumeActor(String resumeId, String companyDomain) {
        // Create a unique persistence ID that includes company domain for better sharding
        String persistenceId = String.format("resume-%s-%s", 
            companyDomain != null ? companyDomain : "default", resumeId);
        
        return resumeActors.computeIfAbsent(persistenceId, pid -> {
            logger.info("Creating new ResumeProcessorActor with persistence ID: {}", pid);
            return actorSystem.systemActorOf(
                ResumeProcessorActor.create(pid),
                "resume-processor-" + pid.hashCode(),
                akka.actor.typed.Props.empty()
            );
        });
    }
    
    /**
     * Clean up actor cache for old resumes to prevent memory leaks
     */
    public void cleanupOldActors() {
        logger.info("Cleaning up old resume actors. Current count: {}", resumeActors.size());
        // In a real implementation, you might want to track last access time
        // and remove actors that haven't been used for a while
        if (resumeActors.size() > 1000) {
            logger.warn("Large number of resume actors in cache: {}", resumeActors.size());
        }
    }
    
    // Result classes
    public static class ResumeProcessingResult {
        public final String resumeId;
        public final boolean success;
        public final String message;
        public final String companyDomain;
        public final long timestamp;
        
        public ResumeProcessingResult(String resumeId, boolean success, String message, 
                                    String companyDomain, long timestamp) {
            this.resumeId = resumeId;
            this.success = success;
            this.message = message;
            this.companyDomain = companyDomain;
            this.timestamp = timestamp;
        }
    }
    
    public static class JobMatchingResult {
        public final String jobId;
        public final String resumeId;
        public final boolean success;
        public final String message;
        public final String companyDomain;
        public final String jobDescription;
        public final long timestamp;
        
        public JobMatchingResult(String jobId, String resumeId, boolean success, String message,
                               String companyDomain, String jobDescription, long timestamp) {
            this.jobId = jobId;
            this.resumeId = resumeId;
            this.success = success;
            this.message = message;
            this.companyDomain = companyDomain;
            this.jobDescription = jobDescription;
            this.timestamp = timestamp;
        }
    }
}