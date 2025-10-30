package com.joborchestratorai.akkajoborchestratorai.controllers;

import com.joborchestratorai.akkajoborchestratorai.services.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/email")
public class EmailController {

    private static final Logger logger = LoggerFactory.getLogger(EmailController.class);

    private final EmailService emailService;

    @Autowired
    public EmailController(EmailService emailService) {
        this.emailService = emailService;
    }

    @PostMapping("/send-bulk")
    public ResponseEntity<Map<String, Object>> sendBulkEmails(
            @RequestBody BulkEmailRequest request) {

        logger.info("Received bulk email request for {} recipients", request.getRecipients().size());

        try {
            // Send emails in bulk
            emailService.sendBulkEmail(
                    request.getRecipients(),
                    request.getSubject(),
                    request.getContent(),
                    request.isHtml(),
                    request.getAttachment(),
                    request.getAttachmentName()
            );

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Emails queued for sending",
                    "recipientsCount", request.getRecipients().size()
            ));

        } catch (Exception e) {
            logger.error("Error sending bulk emails: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to send emails: " + e.getMessage()
            ));
        }
    }

    // Request DTO
    public static class BulkEmailRequest {
        private List<String> recipients;
        private String subject;
        private String content;
        private boolean html = true;
        private byte[] attachment;
        private String attachmentName;

        // Getters and setters
        public List<String> getRecipients() {
            return recipients;
        }

        public void setRecipients(List<String> recipients) {
            this.recipients = recipients;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public boolean isHtml() {
            return html;
        }

        public void setHtml(boolean html) {
            this.html = html;
        }

        public byte[] getAttachment() {
            return attachment;
        }

        public void setAttachment(byte[] attachment) {
            this.attachment = attachment;
        }

        public String getAttachmentName() {
            return attachmentName;
        }

        public void setAttachmentName(String attachmentName) {
            this.attachmentName = attachmentName;
        }
    }
}