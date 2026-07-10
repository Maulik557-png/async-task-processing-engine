package com.poc.taskengine;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.service.TaskService;
import com.poc.taskengine.worker.InvoiceProcessingHandler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 Integration Tests.
 *
 * Verifies:
 * 1. Lock-level Idempotency: Duplicate invoice ID submissions concurrently are rejected.
 * 2. Pool Isolation: Bulk emails do not starve or block critical invoice tasks.
 */
@SpringBootTest
public class Phase6IntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private InvoiceProcessingHandler invoiceHandler;

    private static final java.util.Set<TaskStatus> TERMINAL = java.util.Set.of(
            TaskStatus.COMPLETED, TaskStatus.FAILED,
            TaskStatus.CANCELLED, TaskStatus.TIMED_OUT
    );

    @BeforeEach
    void resetHandler() {
        invoiceHandler.reset();
    }

    // ─── Test 1: Lock-level Idempotency ───────────────────────────────────────

    @Test
    @DisplayName("6.4 — Lock Idempotency: concurrent duplicate invoice submissions are rejected")
    void invoiceProcessing_rejectsDuplicatesConcurrently() throws Exception {
        String invoiceId = "INV-RACE-" + UUID.randomUUID();
        String payload = "{\"invoiceId\":\"" + invoiceId + "\"}";

        // Submit 2 tasks concurrently with the same invoiceId
        int concurrency = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            String taskId = UUID.randomUUID().toString();
            taskIds.add(taskId);
            executor.submit(() -> {
                try {
                    gate.await();
                    taskService.submitTask(
                            TaskType.INVOICE_PROCESSING, TaskPriority.CRITICAL,
                            payload, "idempotency-test", 0, taskId);
                } catch (Exception e) {
                    System.err.println("Failed to submit task: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        gate.countDown(); // release race threads
        done.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Wait for both to terminate (each simulated invoice run takes ~1000ms)
        List<Task> results = new ArrayList<>();
        for (String id : taskIds) {
            results.add(waitForTerminal(id, 10_000));
        }

        // Count COMPLETED vs FAILED
        long completed = results.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
        long failed = results.stream().filter(t -> t.getStatus() == TaskStatus.FAILED).count();

        System.out.println("[IDEMPOTENCY] Concurrently submitted 2 tasks for invoice " + invoiceId);
        System.out.println("[IDEMPOTENCY] Completed count: " + completed + ", Failed count: " + failed);

        assertThat(completed)
                .as("Exactly one task must succeed for a duplicate invoice ID")
                .isEqualTo(1);
        assertThat(failed)
                .as("Exactly one task must fail/be rejected for duplicate invoice ID")
                .isEqualTo(1);

        Task failedTask = results.stream().filter(t -> t.getStatus() == TaskStatus.FAILED).findFirst().orElseThrow();
        assertThat(failedTask.getErrorMessage())
                .as("Rejected task must have a clear duplicate error message")
                .containsIgnoringCase("Duplicate invoice");
    }

    // ─── Test 2: Pool Isolation ───────────────────────────────────────────────

    @Test
    @DisplayName("6.5 — Pool Isolation: burst of slow email tasks does not delay invoice tasks")
    void poolIsolation_emailsDoNotDelayInvoice() throws Exception {
        // Submit 15 slow email tasks (core is 10, max is 20 for bulk pool)
        // Each email takes 500ms, payload doesn't fail.
        int emailCount = 15;
        List<String> emailIds = new ArrayList<>();
        for (int i = 0; i < emailCount; i++) {
            String emailPayload = "{\"recipient\":\"bulk-" + i + "@example.com\",\"subject\":\"Promo\",\"body\":\"Details\"}";
            String id = taskService.submitTask(
                    TaskType.EMAIL_NOTIFICATION, TaskPriority.LOW,
                    emailPayload, "bulk-sender", 0);
            emailIds.add(id);
        }

        // Immediately submit 1 Invoice task. Invoice runs on criticalTaskExecutor (core 3).
        String invoiceId = "INV-ISOLATION-" + UUID.randomUUID();
        String invoicePayload = "{\"invoiceId\":\"" + invoiceId + "\"}";
        Instant submitTime = Instant.now();
        String invoiceTaskId = taskService.submitTask(
                TaskType.INVOICE_PROCESSING, TaskPriority.CRITICAL,
                invoicePayload, "critical-sender", 0);

        // Wait for the invoice task to start/complete.
        Task invoiceTask = waitForTerminal(invoiceTaskId, 5000);

        assertThat(invoiceTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(invoiceTask.getStartedAt()).isNotNull();

        // Verify isolation: the invoice task must have started almost immediately.
        // Even if all bulk threads are busy or the bulk queue is saturated,
        // the invoice task starts on its own thread pool without waiting.
        long startLatencyMs = Duration.between(submitTime, invoiceTask.getStartedAt()).toMillis();
        System.out.println("[ISOLATION] Invoice task submitted alongside 15 emails starts in " + startLatencyMs + "ms");

        assertThat(startLatencyMs)
                .as("Invoice task must start immediately (under 250ms) on its own executor, without queuing behind emails")
                .isLessThan(250);

        // Wait for emails to complete
        for (String emailId : emailIds) {
            waitForTerminal(emailId, 10_000);
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private Task waitForTerminal(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findById(taskId).orElseThrow();
            if (TERMINAL.contains(t.getStatus())) return t;
            Thread.sleep(100);
        }
        return taskRepository.findById(taskId).orElseThrow();
    }
}
