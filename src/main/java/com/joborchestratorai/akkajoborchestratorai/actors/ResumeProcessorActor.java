package com.joborchestratorai.akkajoborchestratorai.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.RecoveryCompleted;
import akka.persistence.typed.javadsl.*;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ResumeProcessorActor extends EventSourcedBehavior<ResumeProcessorActor.Command, ResumeProcessorActor.Event, ResumeProcessorActor.State> {
    private final String persistenceIdStr;

    private ResumeProcessorActor(String persistenceId) {
        super(PersistenceId.ofUniqueId(persistenceId));
        this.persistenceIdStr = persistenceId;
    }
    
    @Override
    public SignalHandler<State> signalHandler() {
        return newSignalHandlerBuilder()
            .onSignal(RecoveryCompleted.class, (state, completed) -> {
                // Log recovery completion
                System.out.println("Recovery completed for resume: " + state.resumeId);
            })
            .build();
    }

    // Commands
    public interface Command {}

    public static final class ProcessResume implements Command {
        public final String resumeId;
        public final String content;
        public final ActorRef<OperationResult> replyTo;

        public ProcessResume(String resumeId, String content, ActorRef<OperationResult> replyTo) {
            this.resumeId = resumeId;
            this.content = content;
            this.replyTo = replyTo;
        }
    }

    public static final class GetStatus implements Command {
        public final String resumeId;
        public final ActorRef<StatusResponse> replyTo;

        public GetStatus(String resumeId, ActorRef<StatusResponse> replyTo) {
            this.resumeId = resumeId;
            this.replyTo = replyTo;
        }
    }

    // Events
    public interface Event {}

    public static final class ResumeProcessed implements Event {
        public final String resumeId;
        public final String content;
        public final long timestamp;

        public ResumeProcessed(String resumeId, String content) {
            this(resumeId, content, System.currentTimeMillis());
        }

        @JsonCreator
        public ResumeProcessed(String resumeId, String content, long timestamp) {
            this.resumeId = resumeId;
            this.content = content;
            this.timestamp = timestamp;
        }
    }

    public static final class ProcessingFailed implements Event {
        public final String resumeId;
        public final String reason;
        public final long timestamp;

        public ProcessingFailed(String resumeId, String reason) {
            this(resumeId, reason, System.currentTimeMillis());
        }

        @JsonCreator
        public ProcessingFailed(String resumeId, String reason, long timestamp) {
            this.resumeId = resumeId;
            this.reason = reason;
            this.timestamp = timestamp;
        }
    }

    // State
    public static final class State {
        public final String resumeId;
        public final List<Event> history;
        public final String status;

        public State(String resumeId, List<Event> history, String status) {
            this.resumeId = resumeId != null ? resumeId : "";
            this.history = history != null ? new ArrayList<>(history) : new ArrayList<>();
            this.status = status != null ? status : "INITIALIZED";
        }

        public State withNewEvent(Event event, String newStatus) {
            List<Event> newHistory = new ArrayList<>(history);
            newHistory.add(event);
            
            // If the event has a resume ID and our current state doesn't have one, use the event's ID
            String newResumeId = this.resumeId;
            if ((newResumeId == null || newResumeId.isEmpty()) && event != null) {
                if (event instanceof ResumeProcessed) {
                    newResumeId = ((ResumeProcessed) event).resumeId;
                } else if (event instanceof ProcessingFailed) {
                    newResumeId = ((ProcessingFailed) event).resumeId;
                }
            }
            
            return new State(newResumeId, newHistory, newStatus);
        }
    }

    // Responses
    public static class OperationResult {
        public final String resumeId;
        public final boolean success;
        public final String message;

        public OperationResult(String resumeId, boolean success, String message) {
            this.resumeId = resumeId;
            this.success = success;
            this.message = message;
        }
    }

    public static class StatusResponse {
        public final String resumeId;
        public final String status;
        public final List<Event> history;

        public StatusResponse(String resumeId, String status, List<Event> history) {
            this.resumeId = resumeId;
            this.status = status;
            this.history = Collections.unmodifiableList(new ArrayList<>(history));
        }
    }

    // Factory method
    public static Behavior<Command> create(String persistenceId) {
        return new ResumeProcessorActor(persistenceId);
    }

    @Override
    public State emptyState() {
        // Initialize with empty resume ID and empty history
        return new State("", new ArrayList<>(), "INITIALIZED");
    }

    @Override
    public CommandHandler<Command, Event, State> commandHandler() {
        CommandHandlerBuilder<Command, Event, State> builder = newCommandHandlerBuilder();
        
        builder.forAnyState()
            .onCommand(ProcessResume.class, this::onProcessResume)
            .onCommand(GetStatus.class, this::onGetStatus);
            
        return builder.build();
    }

    private Effect<Event, State> onProcessResume(State state, ProcessResume cmd) {
        System.out.println("Processing resume: " + cmd.resumeId);
        
        if (cmd.content == null || cmd.content.trim().isEmpty()) {
            System.out.println("Rejecting empty resume content for resume: " + cmd.resumeId);
            // Persist failure event
            return Effect()
                    .persist(new ProcessingFailed(cmd.resumeId, "Content cannot be empty"))
                    .thenRun(newState -> {
                        System.out.println("Sending failure response for resume: " + cmd.resumeId);
                        cmd.replyTo.tell(new OperationResult(cmd.resumeId, false, "Content cannot be empty"));
                    });
        }
        
        System.out.println("Processing resume content for resume: " + cmd.resumeId);
        
        try {
            // Process the resume content (simplified for example)
            // In a real application, this would involve parsing and analyzing the resume
            String processedContent = "PROCESSED: " + cmd.content;
            
            // Persist the processed event
            return Effect()
                    .persist(new ResumeProcessed(cmd.resumeId, processedContent))
                    .thenRun(newState -> {
                        System.out.println("Sending success response for resume: " + cmd.resumeId);
                        cmd.replyTo.tell(new OperationResult(cmd.resumeId, true, "Processing completed"));
                    });
        } catch (Exception e) {
            System.err.println("Error processing resume: " + cmd.resumeId);
            e.printStackTrace();
            return Effect()
                    .persist(new ProcessingFailed(cmd.resumeId, "Error processing resume: " + e.getMessage()))
                    .thenRun(newState -> {
                        System.err.println("Sending error response for resume: " + cmd.resumeId);
                        e.printStackTrace();
                        cmd.replyTo.tell(new OperationResult(cmd.resumeId, false, "Error: " + e.getMessage()));
                    });
        }
    }

    private Effect<Event, State> onGetStatus(State state, GetStatus cmd) {
        System.out.println("Getting status for resume: " + cmd.resumeId + ", current state resumeId: " + state.resumeId);
        
        try {
            // If we have history but no resume ID, check the last event for the resume ID
            String effectiveResumeId = state.resumeId;
            if ((effectiveResumeId == null || effectiveResumeId.isEmpty()) && !state.history.isEmpty()) {
                Event lastEvent = state.history.get(state.history.size() - 1);
                if (lastEvent instanceof ResumeProcessed) {
                    effectiveResumeId = ((ResumeProcessed) lastEvent).resumeId;
                } else if (lastEvent instanceof ProcessingFailed) {
                    effectiveResumeId = ((ProcessingFailed) lastEvent).resumeId;
                }
            }
            
            // If we still don't have a resume ID, return NOT_FOUND
            if (effectiveResumeId == null || effectiveResumeId.isEmpty()) {
                System.out.println("No resume processed yet");
                cmd.replyTo.tell(new StatusResponse("", "NOT_FOUND", Collections.emptyList()));
                return Effect().none();
            }
            
            // If the requested resume ID doesn't match our effective resume ID, return NOT_FOUND
            if (!cmd.resumeId.equals(effectiveResumeId)) {
                System.out.println("Resume ID mismatch. Requested: " + cmd.resumeId + ", Have: " + effectiveResumeId);
                cmd.replyTo.tell(new StatusResponse(cmd.resumeId, "NOT_FOUND", Collections.emptyList()));
                return Effect().none();
            }
            
            // If we have a FAILED status in history, ensure we return that status
            String effectiveStatus = state.status;
            if (state.history.stream().anyMatch(e -> e instanceof ProcessingFailed)) {
                effectiveStatus = "FAILED";
            } else if (state.history.stream().anyMatch(e -> e instanceof ResumeProcessed)) {
                effectiveStatus = "PROCESSED";
            }
            
            System.out.println("Returning status for resume: " + effectiveResumeId + ", status: " + effectiveStatus);
            
            // Return the current status and history
            cmd.replyTo.tell(new StatusResponse(
                effectiveResumeId, 
                effectiveStatus, 
                new ArrayList<>(state.history)
            ));
            return Effect().none();
        } catch (Exception e) {
            System.err.println("Error getting status for resume: " + cmd.resumeId);
            e.printStackTrace();
            // Still try to send a response even if there was an error
            try {
                cmd.replyTo.tell(new StatusResponse(
                    cmd.resumeId, 
                    "ERROR: " + e.getMessage(), 
                    state != null ? new ArrayList<>(state.history) : Collections.emptyList()
                ));
            } catch (Exception ex) {
                System.err.println("Failed to send error response");
                ex.printStackTrace();
            }
            return Effect().none();
        }
    }

    @Override
    public EventHandler<State, Event> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(ResumeProcessed.class, (state, event) -> {
                    System.out.println("Processing ResumeProcessed event for resume: " + event.resumeId);
                    // Always create a new state with the event's resume ID
                    return new State(
                        event.resumeId, 
                        new ArrayList<>(state.history), 
                        "PROCESSED"
                    ).withNewEvent(event, "PROCESSED");
                })
                .onEvent(ProcessingFailed.class, (state, event) -> {
                    System.out.println("Processing ProcessingFailed event for resume: " + event.resumeId);
                    // Always create a new state with the event's resume ID
                    return new State(
                        event.resumeId, 
                        new ArrayList<>(state.history), 
                        "FAILED"
                    ).withNewEvent(event, "FAILED");
                })
                .build();
    }
}
