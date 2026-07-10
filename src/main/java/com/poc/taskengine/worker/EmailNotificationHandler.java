package com.poc.taskengine.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.exception.TaskExecutionException;
import com.poc.taskengine.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes EMAIL_NOTIFICATION tasks.
 *
 * <p>Parses recipient, subject, and body from the JSON payload, simulates
 * an SMTP server call by sleeping ~500ms, and introduces a random 20% failure
 * rate to verify retry mechanics.
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class EmailNotificationHandler implements TaskHandler {

    private final ObjectMapper objectMapper;

    @Override
    public TaskType getSupportedType() {
        return TaskType.EMAIL_NOTIFICATION;
    }

    @Override
    public TaskResult execute(Task task) throws TaskExecutionException {
        String payload = task.getPayload();
        String threadName = Thread.currentThread().getName();

        try {
            // Parse recipient, subject, body from the task's JSON payload.
            JsonNode root = objectMapper.readTree(payload);
            String recipient = root.path("recipient").asText("unknown@example.com");
            String subject = root.path("subject").asText("No Subject");
            String body = root.path("body").asText("No Body");

            // Log details.
            log.info("[{}] Processing Email — Recipient: {}, Subject: '{}', Body Preview: '{}'",
                    threadName, recipient, subject, body.substring(0, Math.min(body.length(), 30)));

            // Simulate SMTP network call delay (~500ms).
            Thread.sleep(500);

            // Introduce 20% random failure rate to verify retry mechanism.
            if (ThreadLocalRandom.current().nextDouble() < 0.20) {
                log.warn("[{}] Simulated random SMTP delivery failure for recipient: {}", threadName, recipient);
                throw new TaskExecutionException("Simulated SMTP delivery failure (network timeout)");
            }

            log.info("[{}] Email sent successfully to {}", threadName, recipient);
            return new TaskResult("Email sent successfully to " + recipient);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException("Email processing interrupted", e);
        } catch (Exception e) {
            if (e instanceof TaskExecutionException) {
                throw (TaskExecutionException) e;
            }
            throw new TaskExecutionException("Failed to parse or process email payload: " + e.getMessage(), e);
        }
    }
}
