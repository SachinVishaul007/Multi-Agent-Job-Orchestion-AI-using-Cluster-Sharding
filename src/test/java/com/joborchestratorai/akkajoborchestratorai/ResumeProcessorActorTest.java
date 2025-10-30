package com.joborchestratorai.akkajoborchestratorai;

import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.typed.ActorRef;
import com.joborchestratorai.akkajoborchestratorai.actors.ResumeProcessorActor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
public class ResumeProcessorActorTest {

    private static ActorTestKit testKit;
    private static ActorRef<ResumeProcessorActor.Command> processor;

    @BeforeAll
    public static void setup() {
        // Create a test actor system with persistence configuration
        String configString = """
            akka {
              actor {
                provider = local
                debug {
                  receive = on
                  lifecycle = on
                  unhandled = on
                  event-stream = on
                }
              }
              loglevel = DEBUG
              log-dead-letters = on
              log-dead-letters-during-shutdown = on
              logger-startup-timeout = 10s
              loggers = ["akka.testkit.TestEventListener"]
              
              persistence {
                journal {
                  plugin = akka.persistence.journal.inmem
                  inmem {
                    class = "akka.persistence.journal.inmem.InmemJournal"
                    event-adapters {
                      // No custom adapters needed for this test
                    }
                    event-adapter-bindings {
                      // No custom bindings needed for this test
                    }
                  }
                }
                snapshot-store {
                  plugin = akka.persistence.snapshot-store.local
                  local.dir = "target/snapshots/"
                }
              }
            }
            akka.test.single-expect-default = 10s
            akka.test.timefactor = 2.0
            """;
            
        Config config = ConfigFactory.parseString(configString);
        
        System.out.println("Creating ActorTestKit with config: " + config);
        
        try {
            testKit = ActorTestKit.create("test-system", config);
            System.out.println("ActorTestKit created successfully");
            
            // Create a persistent actor for testing
            processor = testKit.spawn(ResumeProcessorActor.create("test-resume-1"));
            System.out.println("ResumeProcessor actor created with persistence ID: test-resume-1");
            
        } catch (Exception e) {
            System.err.println("Error setting up test: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    @AfterAll
    public static void cleanup() {
        if (testKit != null) {
            testKit.shutdownTestKit();
        }
    }

    @Test
    public void testProcessResume() {
        // Create a unique resume ID for this test
        String resumeId = "resume-testProcessResume-" + System.currentTimeMillis();
        System.out.println("Testing resume processing for ID: " + resumeId);
        
        TestProbe<ResumeProcessorActor.OperationResult> probe = testKit.createTestProbe();
        
        // Send the process resume command
        processor.tell(new ResumeProcessorActor.ProcessResume(
                resumeId, 
                "John Doe's resume content for testProcessResume", 
                probe.ref()
        ));
        
        // Wait for the response with a timeout
        ResumeProcessorActor.OperationResult result = probe.receiveMessage(Duration.ofSeconds(30));
        assertNotNull(result, "Should receive a response");
        assertTrue(result.success, "Processing should be successful. Message: " + result.message);
        assertEquals(resumeId, result.resumeId, "Resume ID should match");
        
        // Test getting status
        TestProbe<ResumeProcessorActor.StatusResponse> statusProbe = testKit.createTestProbe();
        processor.tell(new ResumeProcessorActor.GetStatus(resumeId, statusProbe.ref()));
        
        // Verify status
        ResumeProcessorActor.StatusResponse status = statusProbe.receiveMessage(Duration.ofSeconds(30));
        assertNotNull(status, "Should receive status response");
        
        // The resume ID in the status should match the one we sent
        assertEquals(resumeId, status.resumeId, "Resume ID in status should match");
        
        // The status should be PROCESSED after successful processing
        assertEquals("PROCESSED", status.status, "Status should be PROCESSED");
        
        // History should not be empty
        assertFalse(status.history.isEmpty(), "History should not be empty");
        
        // Check if any event is a ResumeProcessed event
        boolean hasProcessedEvent = status.history.stream()
            .anyMatch(e -> e instanceof ResumeProcessorActor.ResumeProcessed);
        assertTrue(hasProcessedEvent, "Should have ResumeProcessed event in history");
    }
    
    @Test
    public void testEmptyResume() {
        String resumeId = "empty-resume-testEmptyResume-" + System.currentTimeMillis();
        System.out.println("Testing empty resume with ID: " + resumeId);
        
        TestProbe<ResumeProcessorActor.OperationResult> probe = testKit.createTestProbe();
        
        // Test empty content
        processor.tell(new ResumeProcessorActor.ProcessResume(
                resumeId, 
                "", // Empty content
                probe.ref()
        ));
        
        // Verify failure for empty content
        ResumeProcessorActor.OperationResult result = probe.receiveMessage(Duration.ofSeconds(30));
        assertNotNull(result, "Should receive a response");
        assertFalse(result.success, "Processing should fail for empty content");
        assertEquals(resumeId, result.resumeId, "Resume ID should match");
        assertTrue(result.message != null && !result.message.isEmpty(), 
            "Error message should be present but was: " + result.message);
        
        // Verify status shows as failed
        TestProbe<ResumeProcessorActor.StatusResponse> statusProbe = testKit.createTestProbe();
        processor.tell(new ResumeProcessorActor.GetStatus(resumeId, statusProbe.ref()));
        
        // Verify failed status
        ResumeProcessorActor.StatusResponse status = statusProbe.receiveMessage(Duration.ofSeconds(30));
        assertNotNull(status, "Should receive status response");
        assertEquals(resumeId, status.resumeId, "Resume ID in status should match");
        assertEquals("FAILED", status.status, "Status should be FAILED for empty content");
        assertFalse(status.history.isEmpty(), "History should not be empty");
        
        // Check if any event is a ProcessingFailed event
        boolean hasFailedEvent = status.history.stream()
            .anyMatch(e -> e instanceof ResumeProcessorActor.ProcessingFailed);
        assertTrue(hasFailedEvent, "Should have ProcessingFailed event in history");
    }
    
    @Test
    public void testPersistence() {
        String resumeId = "persistence-test-" + System.currentTimeMillis();
        String content = "Persistent resume content for testPersistence";
        System.out.println("Testing persistence with resume ID: " + resumeId);
        
        // Create a unique persistence ID for this test
        String persistenceId = "test-persistence-" + UUID.randomUUID();
        
        // First, create and process a resume with the first actor
        ActorRef<ResumeProcessorActor.Command> firstProcessor = testKit.spawn(ResumeProcessorActor.create(persistenceId));
        
        try {
            // Process a resume with the first actor
            TestProbe<ResumeProcessorActor.OperationResult> probe1 = testKit.createTestProbe();
            firstProcessor.tell(new ResumeProcessorActor.ProcessResume(resumeId, content, probe1.ref()));
            
            // Wait for processing to complete
            ResumeProcessorActor.OperationResult result = probe1.receiveMessage(Duration.ofSeconds(30));
            assertNotNull(result, "Should receive a response from first processor");
            assertTrue(result.success, "First processing should be successful. Message: " + result.message);
            
            // Stop the first processor to simulate a system restart
            testKit.stop(firstProcessor);
            
            // Create a second processor with the same persistence ID
            ActorRef<ResumeProcessorActor.Command> secondProcessor = testKit.spawn(ResumeProcessorActor.create(persistenceId));
            
            try {
                // Verify state was recovered by checking status
                TestProbe<ResumeProcessorActor.StatusResponse> statusProbe = testKit.createTestProbe();
                secondProcessor.tell(new ResumeProcessorActor.GetStatus(resumeId, statusProbe.ref()));
                
                // Verify recovered state
                ResumeProcessorActor.StatusResponse status = statusProbe.receiveMessage(Duration.ofSeconds(30));
                assertNotNull(status, "Should receive status response after recovery");
                
                // The resume ID in the recovered state should match
                assertEquals(resumeId, status.resumeId, "Recovered resume ID should match");
                
                // The status should be PROCESSED after recovery
                assertEquals("PROCESSED", status.status, "Status should be PROCESSED after recovery");
                
                // History should not be empty
                assertFalse(status.history.isEmpty(), "History should not be empty after recovery");
                
                // Check if any event is a ResumeProcessed event
                boolean hasProcessedEvent = status.history.stream()
                    .anyMatch(e -> e instanceof ResumeProcessorActor.ResumeProcessed);
                assertTrue(hasProcessedEvent, "Should have ResumeProcessed event in history after recovery");
                
            } finally {
                testKit.stop(secondProcessor);
            }
            
        } finally {
            testKit.stop(firstProcessor);
        }
    }
}
