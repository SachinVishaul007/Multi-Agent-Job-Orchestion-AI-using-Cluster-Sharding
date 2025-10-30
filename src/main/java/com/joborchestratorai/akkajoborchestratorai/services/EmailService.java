package com.joborchestratorai.akkajoborchestratorai.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.List;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${spring.mail.personal:Job Orchestrator}")
    private String personalName;

    /**
     * Send an email to a single recipient
     */
    public void sendEmail(String to, String subject, String content, boolean isHtml) {
        sendEmail(List.of(to), subject, content, isHtml, null, null);
    }

    /**
     * Send an email to multiple recipients
     */
    public void sendEmail(List<String> to, String subject, String content, boolean isHtml) {
        sendEmail(to, subject, content, isHtml, null, null);
    }

    /**
     * Send an email with an attachment
     */
    public void sendEmailWithAttachment(List<String> to, String subject, String content,
                                        boolean isHtml, String attachmentName, byte[] attachment) {
        sendEmail(to, subject, content, isHtml, attachmentName, attachment);
    }

    private void sendEmail(List<String> to, String subject, String content,
                           boolean isHtml, String attachmentName, byte[] attachment) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            // Set sender with personal name
            helper.setFrom(String.format("%s <%s>", personalName, fromEmail));
            helper.setTo(to.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(content, isHtml);

            // Add attachment if provided
            if (attachment != null && attachmentName != null) {
                helper.addAttachment(attachmentName, new ByteArrayResource(attachment));
            }

            mailSender.send(message);
            logger.info("Email sent successfully to: {}", String.join(", ", to));

        } catch (MessagingException e) {
            logger.error("Failed to send email to {}: {}", String.join(", ", to), e.getMessage(), e);
            throw new RuntimeException("Failed to send email", e);
        }
    }

    /**
     * Send a bulk email with the same content to multiple recipients
     * @param recipients List of email addresses
     * @param subjectTemplate Email subject (can contain placeholders like {name}, {email}, etc.)
     * @param contentTemplate Email content (can contain placeholders like {name}, {email}, etc.)
     * @param isHtml Whether the content is HTML
     * @param attachment Optional attachment
     * @param attachmentName Name of the attachment if provided
     */
    public void sendBulkEmail(List<String> recipients, String subjectTemplate,
                              String contentTemplate, boolean isHtml,
                              byte[] attachment, String attachmentName) {
        for (String recipient : recipients) {
            try {
                // Replace placeholders in subject and content
                String personalizedSubject = subjectTemplate
                        .replace("{email}", recipient)
                        .replace("{name}", recipient.split("@")[0]);

                String personalizedContent = contentTemplate
                        .replace("{email}", recipient)
                        .replace("{name}", recipient.split("@")[0]);

                if (attachment != null && attachmentName != null) {
                    sendEmailWithAttachment(
                            List.of(recipient),
                            personalizedSubject,
                            personalizedContent,
                            isHtml,
                            attachmentName,
                            attachment
                    );
                } else {
                    sendEmail(
                            List.of(recipient),
                            personalizedSubject,
                            personalizedContent,
                            isHtml
                    );
                }
            } catch (Exception e) {
                logger.error("Failed to send email to {}: {}", recipient, e.getMessage());
                // Continue with next recipient even if one fails
            }
        }
    }
}