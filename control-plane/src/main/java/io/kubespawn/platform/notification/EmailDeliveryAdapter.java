package io.kubespawn.platform.notification;

import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailDeliveryAdapter {
    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryAdapter.class);

    private final String mailpitUrl;
    private final String senderEmail;
    private final String senderName;
    private final String portalBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmailDeliveryAdapter(
        @Value("${kubespawn.notifications.mailpit-url:http://localhost:8025/api/v1/send}") String mailpitUrl,
        @Value("${kubespawn.notifications.sender-email:notifications@kubespawn.io}") String senderEmail,
        @Value("${kubespawn.notifications.sender-name:KubeSpawn}") String senderName,
        @Value("${kubespawn.notifications.portal-base-url:http://localhost:5173}") String portalBaseUrl,
        ObjectMapper objectMapper
    ) {
        this.mailpitUrl = mailpitUrl;
        this.senderEmail = senderEmail;
        this.senderName = senderName;
        this.portalBaseUrl = portalBaseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    }

    public static class EmailDeliveryException extends RuntimeException {
        public EmailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public void sendEmail(String recipientEmail, String recipientName, String subject, String type, String message, String targetUrl) {
        try {
            String fullTargetUrl = (targetUrl != null && !targetUrl.isBlank()) 
                ? (portalBaseUrl + (targetUrl.startsWith("/") ? targetUrl : "/" + targetUrl))
                : portalBaseUrl;

            String textBody = buildPlainTextBody(type, subject, message, fullTargetUrl);
            String htmlBody = buildHtmlBody(type, subject, message, fullTargetUrl);

            Map<String, Object> payload = Map.of(
                "From", Map.of("Email", senderEmail, "Name", senderName),
                "To", List.of(Map.of("Email", recipientEmail, "Name", recipientName != null ? recipientName : "")),
                "Subject", subject,
                "Text", textBody,
                "HTML", htmlBody
            );

            String jsonPayload = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mailpitUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                log.warn("Failed to deliver email to {}: status={} body={}", recipientEmail, response.statusCode(), response.body());
                throw new EmailDeliveryException("Mailpit returned status " + response.statusCode() + ": " + response.body(), null);
            }

            log.info("Notification email sent successfully to {} [Subject: '{}']", recipientEmail, subject);
        } catch (EmailDeliveryException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Error delivering email to {}: {}", recipientEmail, e.getMessage());
            throw new EmailDeliveryException("Could not send email to " + recipientEmail + ": " + e.getMessage(), e);
        }
    }

    private String buildPlainTextBody(String type, String subject, String message, String fullTargetUrl) {
        return """
            KubeSpawn Notification: %s
            --------------------------------------------------
            %s
            
            %s
            
            View in KubeSpawn Workspace: %s
            
            --------------------------------------------------
            This is an automated notification from KubeSpawn Developer Platform.
            Confidential: Authentication credentials and preview exchange tokens are never sent via email.
            """.formatted(type, subject, message, fullTargetUrl);
    }

    private String buildHtmlBody(String type, String subject, String message, String fullTargetUrl) {
        String safeSubject = escapeHtml(subject);
        String safeMessage = escapeHtml(message);
        String safeType = escapeHtml(type.replace('_', ' '));

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #0b0f19; color: #f1f5f9; margin: 0; padding: 24px; }
                .container { max-width: 600px; margin: 0 auto; background: #131b2e; border: 1px solid #1e293b; border-radius: 8px; overflow: hidden; }
                .header { background: #0f172a; padding: 20px 24px; border-bottom: 1px solid #1e293b; display: flex; align-items: center; }
                .logo { font-size: 18px; font-weight: 700; color: #38bdf8; text-decoration: none; }
                .content { padding: 24px; }
                .badge { display: inline-block; padding: 4px 10px; font-size: 11px; font-weight: 600; text-transform: uppercase; border-radius: 4px; background: rgba(56, 189, 248, 0.15); color: #38bdf8; margin-bottom: 12px; }
                h2 { margin: 0 0 16px 0; font-size: 18px; color: #f8fafc; font-weight: 600; }
                p { margin: 0 0 20px 0; font-size: 14px; line-height: 1.6; color: #94a3b8; }
                .cta-btn { display: inline-block; padding: 10px 20px; font-size: 13px; font-weight: 600; color: #ffffff; background: #2563eb; border-radius: 6px; text-decoration: none; }
                .footer { background: #0f172a; padding: 16px 24px; border-top: 1px solid #1e293b; font-size: 12px; color: #64748b; line-height: 1.5; }
              </style>
            </head>
            <body>
              <div class="container">
                <div class="header">
                  <span class="logo">KubeSpawn</span>
                </div>
                <div class="content">
                  <span class="badge">%s</span>
                  <h2>%s</h2>
                  <p>%s</p>
                  <a href="%s" class="cta-btn">View in KubeSpawn</a>
                </div>
                <div class="footer">
                  This is an automated notification from KubeSpawn Developer Platform.<br>
                  Security Policy: Authentication credentials and preview tokens are never included in email.
                </div>
              </div>
            </body>
            </html>
            """.formatted(safeType, safeSubject, safeMessage, fullTargetUrl);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
