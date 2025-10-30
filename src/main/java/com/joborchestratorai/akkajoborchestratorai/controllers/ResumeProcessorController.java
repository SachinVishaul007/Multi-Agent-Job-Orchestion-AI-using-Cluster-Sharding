package com.joborchestratorai.akkajoborchestratorai.controllers;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Scheduler;
import akka.actor.typed.SpawnProtocol;
import akka.actor.typed.javadsl.AskPattern;
import com.joborchestratorai.akkajoborchestratorai.actors.ResumeProcessorActor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/resume")
public class ResumeProcessorController {

    private final ActorSystem<SpawnProtocol.Command> system;
    private final Scheduler scheduler;
    private final Duration timeout = Duration.ofSeconds(5);
    private final Map<String, ActorRef<ResumeProcessorActor.Command>> processors = new ConcurrentHashMap<>();

    @Autowired
    public ResumeProcessorController(ActorSystem<SpawnProtocol.Command> system) {
        this.system = system;
        this.scheduler = system.scheduler();
    }

    private ActorRef<ResumeProcessorActor.Command> getOrCreateProcessor(String resumeId) {
        return processors.computeIfAbsent(resumeId, id -> {
            // Create a new persistent actor for this resume
            return system.systemActorOf(
                ResumeProcessorActor.create("resume-" + resumeId),
                "resume-processor-" + resumeId,
                akka.actor.typed.Props.empty()
            );
        });
    }

    @PostMapping("/process")
    public CompletionStage<ResponseEntity<String>> processResume(
            @RequestParam String resumeId,
            @RequestParam String content) {
        
        ActorRef<ResumeProcessorActor.Command> processor = getOrCreateProcessor(resumeId);
        
        // Use AskPattern for request-response
        CompletionStage<ResumeProcessorActor.OperationResult> resultFuture = AskPattern.ask(
            processor,
            replyTo -> new ResumeProcessorActor.ProcessResume(resumeId, content, replyTo),
            Duration.ofSeconds(30),
            system.scheduler()
        );
        
        // Map the result to a ResponseEntity
        return resultFuture.handle((result, throwable) -> {
            if (throwable != null) {
                return ResponseEntity.status(500)
                    .body("Error processing resume: " + throwable.getMessage());
            }
            if (result.success) {
                return ResponseEntity.ok("Resume processed successfully: " + result.message);
            } else {
                return ResponseEntity.badRequest()
                    .body("Failed to process resume: " + result.message);
            }
        });
    }

    @GetMapping("/status/{resumeId}")
    public CompletionStage<ResponseEntity<?>> getStatus(@PathVariable String resumeId) {
        ActorRef<ResumeProcessorActor.Command> processor = getOrCreateProcessor(resumeId);
        
        // Use AskPattern for request-response
        CompletionStage<ResumeProcessorActor.StatusResponse> statusFuture = AskPattern.ask(
            processor,
            replyTo -> new ResumeProcessorActor.GetStatus(resumeId, replyTo),
            Duration.ofSeconds(10),
            system.scheduler()
        );
        
        // Map the response to a ResponseEntity
        return statusFuture.handle((result, throwable) -> {
            if (throwable != null) {
                return ResponseEntity.status(500)
                    .body("Error getting status: " + throwable.getMessage());
            }
            return ResponseEntity.ok(result);
        });
    }
}
