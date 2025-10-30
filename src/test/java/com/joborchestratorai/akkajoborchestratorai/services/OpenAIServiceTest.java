package com.joborchestratorai.akkajoborchestratorai.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OpenAIServiceTest {

    @Mock
    private Environment environment;
    
    @Mock
    private LocalStorageService localStorageService;
    
    private OpenAIService openAIService;
    private String openAIApiKey;

    @BeforeEach
    public void setUp() {
        // Get API key from environment variable or use null if not set
        openAIApiKey = System.getenv("OPENAI_API_KEY");
        
        // Initialize the service with mocks
        openAIService = new OpenAIService(localStorageService);
        
        // Use reflection to set the fields for testing
        try {
            // Set API key
            java.lang.reflect.Field apiKeyField = OpenAIService.class.getDeclaredField("apiKey");
            apiKeyField.setAccessible(true);
            apiKeyField.set(openAIService, openAIApiKey);
            
            // Set model
            java.lang.reflect.Field modelField = OpenAIService.class.getDeclaredField("model");
            modelField.setAccessible(true);
            modelField.set(openAIService, "gpt-3.5-turbo"); // Use a more widely available model for testing
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up test environment", e);
        }
    }

    @Test
    public void testOpenAIConnection() {
        // Skip test if no API key is provided
        assumeTrue(openAIApiKey != null && !openAIApiKey.trim().isEmpty(), 
            "Skipping test: OPENAI_API_KEY environment variable not set");
            
        try {
            // Test with a common name and domain
            String testName = "John Doe";
            String testDomain = "example.com";
            
            System.out.println("Testing OpenAI API connection...");
            System.out.println("Name: " + testName);
            System.out.println("Domain: " + testDomain);
            
            // Make the API call
            String predictedEmail = openAIService.predictEmailAddress(testName, testDomain);
            
            // If we get here, the API call was successful
            System.out.println("OpenAI API test successful!");
            System.out.println("Predicted email: " + predictedEmail);
            
            // Basic validation of the response
            assertNotNull(predictedEmail, "Predicted email should not be null");
            assertTrue(predictedEmail.contains("@"), "Predicted email should contain @ symbol");
            assertTrue(predictedEmail.toLowerCase().endsWith(testDomain.toLowerCase()), 
                "Predicted email should end with the test domain");
            
        } catch (Exception e) {
            System.err.println("\n=== OpenAI API Test Failed ===");
            System.err.println("Error: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Cause: " + e.getCause().getMessage());
            }
            System.err.println("\nTo run this test, you need to:");
            System.err.println("1. Set up a valid OpenAI API key");
            System.err.println("2. Set it as an environment variable: OPENAI_API_KEY=your_api_key_here");
            System.err.println("3. Ensure your account has an active billing setup");
            System.err.println("4. Check your usage limits at https://platform.openai.com/account/usage");
            System.err.println("\nNote: This test makes actual API calls to OpenAI");
            fail("OpenAI API test failed. See console for details.");
        }
    }
}
