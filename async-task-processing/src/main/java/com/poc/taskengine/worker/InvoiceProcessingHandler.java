package com.poc.taskengine.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.exception.TaskExecutionException;
import com.poc.taskengine.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes INVOICE_PROCESSING tasks.
 *
 * <p>Uses a {@link ReentrantLock} and a processed invoice ID set to guarantee
 * lock-level idempotency and prevent duplicate execution of critical financial tasks.
 */
@Slf4j
@Component
public class InvoiceProcessingHandler implements TaskHandler {

    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> processedInvoiceIds = new HashSet<>();

    public InvoiceProcessingHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskType getSupportedType() {
        return TaskType.INVOICE_PROCESSING;
    }

    @Override
    public TaskResult execute(Task task) throws TaskExecutionException {
        String payload = task.getPayload();
        String threadName = Thread.currentThread().getName();

        try {
            // Parse invoiceId from payload
            JsonNode root = objectMapper.readTree(payload);
            JsonNode invoiceIdNode = root.path("invoiceId");
            if (invoiceIdNode.isMissingNode() || invoiceIdNode.isNull() || invoiceIdNode.asText().isBlank()) {
                throw new TaskExecutionException("Invoice payload is missing invoiceId: " + payload);
            }
            String invoiceId = invoiceIdNode.asText();

            // Lock-level idempotency guard
            lock.lock();
            try {
                if (processedInvoiceIds.contains(invoiceId)) {
                    log.warn("[{}] Duplicate Invoice Rejection: invoiceId [{}] has already been processed (task [{}]).",
                            threadName, invoiceId, task.getTaskId());
                    throw new TaskExecutionException("Duplicate invoice rejection: ID " + invoiceId + " already processed");
                }
                // Register the invoice ID under lock to guarantee mutual exclusion
                processedInvoiceIds.add(invoiceId);
                log.info("[{}] Invoice [{}] registered for processing (under lock)", threadName, invoiceId);
            } finally {
                lock.unlock();
            }

            // Simulate critical financial work (~1000ms)
            log.info("[{}] Processing invoice [{}]...", threadName, invoiceId);
            Thread.sleep(1000);

            log.info("[{}] Invoice [{}] processed successfully", threadName, invoiceId);
            return new TaskResult("Invoice processed successfully: " + invoiceId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException("Invoice processing interrupted", e);
        } catch (Exception e) {
            if (e instanceof TaskExecutionException) {
                throw (TaskExecutionException) e;
            }
            throw new TaskExecutionException("Failed to process invoice task: " + e.getMessage(), e);
        }
    }

    /** Expose for testing to reset the state between runs. */
    public void reset() {
        lock.lock();
        try {
            processedInvoiceIds.clear();
        } finally {
            lock.unlock();
        }
    }
}
