package com.poc.taskengine;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.service.CircuitBreakerRegistry;
import com.poc.taskengine.service.TaskService;
import com.poc.taskengine.worker.TaskWorker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 Resilience Integration Tests.
 *
 * Tests verify:
 * 1. RETRY SUCCESS: A task that fails N times but has maxRetries > N eventually COMPLETES.
 *    - retryCount reaches N before the successful attempt.
 *
 * 2. RETRY EXHAUSTION: A task that always fails and exhausts maxRetries ends up FAILED.
 *    - retryCount equals maxRetries when permanently FAILED.
 *
 * 3. CIRCUIT BREAKER: 10+ consecutive permanent failures for EMAIL_NOTIFICATION trigger
 *    a WARNING log with "CIRCUIT BREAKER THRESHOLD REACHED".
 *    - Verified via CircuitBreakerRegistry.getConsecutiveFailures().
 *
 * ── RACE CONDITION MITIGATION ───────────────────────────────────────────────
 * Tests use the 6-arg submitTask() overload (caller provides UUID) so the force-fail
 * entry can be pre-registered in TaskWorker.forceFailCounts BEFORE the task is submitted.
 * Without this, the 5 idle worker threads can pick up the task immediately after
 * submitTask() returns, before the test thread reaches forceFailCounts.put() — causing
 * the worker to see null in the map and skip the forced failure.
 *
 * Sequence with pre-registration (race-free):
 *   1. test: UUID id = UUID.randomUUID().toString()
 *   2. test: TaskWorker.forceFailCounts.put(id, new AtomicInteger(N)) ← put FIRST
 *   3. test: taskService.submitTask(..., id)   ← worker can start now, map entry exists
 *   4. worker: forceFailCounts.get(id) → AtomicInteger(N) → force-fail fires correctly
 *
 * @TestPropertySource: override retry delays to milliseconds so tests run fast.
 *   Production: base-delay=1000ms, jitter=100ms.
 *   Test:       base-delay=200ms,  jitter=10ms.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "task.retry.base-delay-ms=200",
        "task.retry.max-jitter-ms=10",
        "task.retry.permit-timeout-seconds=5",
        "task.timeout.seconds=300",       // prevent watchdog from evicting test tasks
        "task.watchdog.interval-ms=60000"  // watchdog fires once per minute during tests
})
class RetryIntegrationTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreaker;

    private static final java.util.Set<TaskStatus> TERMINAL = java.util.Set.of(
            TaskStatus.COMPLETED, TaskStatus.FAILED,
            TaskStatus.CANCELLED, TaskStatus.TIMED_OUT
    );

    @AfterEach
    void cleanup() {
        // Clear force-fail map after each test to prevent cross-test contamination.
        TaskWorker.forceFailCounts.clear();
    }

    // ─── Test 1: Retry eventually succeeds ────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("5.1 — Task fails twice, succeeds on 3rd attempt (retryCount=2, COMPLETED)")
    void retryEventuallySucceeds() throws Exception {
        // PRE-REGISTER force-fail BEFORE submitting to eliminate the race condition.
        // The worker thread will see the entry immediately when it starts.
        String id = UUID.randomUUID().toString();
        TaskWorker.forceFailCounts.put(id, new AtomicInteger(2)); // fail first 2 attempts

        taskService.submitTask(
                TaskType.DATA_EXPORT, TaskPriority.HIGH,
                "{\"test\":\"retry-success\"}", "retry-test", 3, id);

        // Budget: 2 failures (instant) + delays ~200ms + ~400ms + 1 success (≤2500ms) < 15s
        Task task = waitForTerminal(id, 15_000);

        System.out.println("[RETRY_SUCCESS] Task [" + id + "] "
                + "status=" + task.getStatus()
                + " retryCount=" + task.getRetryCount()
                + " result=" + task.getResult());

        assertThat(task.getStatus())
                .as("Task must eventually COMPLETE after retries")
                .isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getRetryCount())
                .as("Task must have retried exactly 2 times before succeeding")
                .isEqualTo(2);
        assertThat(task.getResult())
                .as("Completed task must have a result")
                .isNotBlank();
    }

    // ─── Test 2: Retry exhaustion → permanent FAILED ──────────────────────────

    @Test
    @Order(2)
    @DisplayName("5.1 — Task fails all 3 attempts, exhausts maxRetries=2, permanently FAILED")
    void retryExhausted_permanentlyFailed() throws Exception {
        String id = UUID.randomUUID().toString();
        // maxRetries=2 → 3 total attempts (original + 2 retries). Always fail.
        TaskWorker.forceFailCounts.put(id, new AtomicInteger(Integer.MAX_VALUE));

        taskService.submitTask(
                TaskType.DATA_EXPORT, TaskPriority.NORMAL,
                "{\"test\":\"retry-exhaust\"}", "retry-test", 2, id);

        // Budget: 3 instant failures + delays ~200ms + ~400ms = ~600ms + margin = 20s
        Task task = waitForTerminal(id, 20_000);

        System.out.println("[RETRY_EXHAUST] Task [" + id + "] "
                + "status=" + task.getStatus()
                + " retryCount=" + task.getRetryCount()
                + " errorMessage=" + task.getErrorMessage());

        assertThat(task.getStatus())
                .as("Task must be permanently FAILED after exhausting retries")
                .isEqualTo(TaskStatus.FAILED);
        assertThat(task.getRetryCount())
                .as("retryCount must equal maxRetries when permanently FAILED")
                .isEqualTo(2);
        assertThat(task.getErrorMessage())
                .as("Error message must be set on the final failure")
                .isNotBlank();
    }

    // ─── Test 3: Circuit breaker threshold ────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("5.5 — 10 consecutive permanent EMAIL_NOTIFICATION failures trigger circuit breaker")
    void circuitBreaker_triggeredAfter10ConsecutiveFailures() throws Exception {
        // Reset circuit breaker for clean starting state.
        circuitBreaker.recordSuccess(TaskType.EMAIL_NOTIFICATION);
        assertThat(circuitBreaker.getConsecutiveFailures(TaskType.EMAIL_NOTIFICATION)).isZero();

        // Submit 10 EMAIL_NOTIFICATION tasks: maxRetries=0 → single attempt, permanent FAILED.
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = UUID.randomUUID().toString();
            TaskWorker.forceFailCounts.put(id, new AtomicInteger(Integer.MAX_VALUE));
            taskService.submitTask(
                    TaskType.EMAIL_NOTIFICATION, TaskPriority.LOW,
                    "{\"test\":\"cb-" + i + "\"}", "cb-test", 0, id);
            ids.add(id);
        }

        // Wait for all 10 to permanently FAIL (no retries → fast).
        long deadline = System.currentTimeMillis() + 30_000;
        for (String id : ids) {
            long remaining = deadline - System.currentTimeMillis();
            Task task = waitForTerminal(id, remaining);
            assertThat(task.getStatus())
                    .as("Task [" + id + "] must reach terminal state")
                    .isIn(TERMINAL);
        }

        // Circuit breaker must have fired at threshold.
        int failures = circuitBreaker.getConsecutiveFailures(TaskType.EMAIL_NOTIFICATION);
        System.out.println("[CIRCUIT_BREAKER] Consecutive permanent failures for EMAIL_NOTIFICATION: "
                + failures + " (threshold=10)");
        assertThat(failures)
                .as("Circuit breaker must have recorded at least 10 consecutive permanent failures")
                .isGreaterThanOrEqualTo(10);

        // A subsequent success must reset the counter.
        String successId = UUID.randomUUID().toString();
        // No forceFailCounts entry → this task succeeds naturally.
        taskService.submitTask(
                TaskType.EMAIL_NOTIFICATION, TaskPriority.LOW,
                "{\"test\":\"cb-reset\"}", "cb-test", 0, successId);
        Task successTask = waitForTerminal(successId, 10_000);
        assertThat(successTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);

        int afterReset = circuitBreaker.getConsecutiveFailures(TaskType.EMAIL_NOTIFICATION);
        System.out.println("[CIRCUIT_BREAKER] Counter after success: " + afterReset);
        assertThat(afterReset)
                .as("Circuit breaker counter must reset to 0 after a success")
                .isZero();
    }

    // ─── Helper ─────────────────────────────────────────────────────────────────────

    /**
     * Wait until the task reaches a truly terminal state.
     *
     * <p><strong>Transient FAILED handling:</strong>
     * The retry flow transitions IN_PROGRESS → FAILED before scheduling the next attempt.
     * The task sits in FAILED for N milliseconds while the retry-scheduler timer counts
     * down, then retryTask() transitions it FAILED → PENDING → IN_PROGRESS again.
     *
     * <p>Polling on FAILED alone is therefore wrong — we must also check that retries
     * are exhausted ({@code retryCount >= maxRetries}). Only then is FAILED permanent.
     */
    private Task waitForTerminal(String taskId, long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) timeoutMs = 5_000;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findById(taskId).orElseThrow();
            if (TERMINAL.contains(t.getStatus())) {
                // FAILED is only truly terminal when all retries are exhausted.
                // retryCount < maxRetries means the retry scheduler will fire soon
                // and transition the task back to PENDING — keep polling.
                if (t.getStatus() == TaskStatus.FAILED && t.getRetryCount() < t.getMaxRetries()) {
                    Thread.sleep(150); // short interval — retry fires soon
                    continue;
                }
                return t;
            }
            Thread.sleep(300);
        }
        Task t = taskRepository.findById(taskId).orElseThrow();
        System.err.println("[TIMEOUT] Task [" + taskId + "] still "
                + t.getStatus() + " retryCount=" + t.getRetryCount()
                + " maxRetries=" + t.getMaxRetries() + " after " + timeoutMs + "ms");
        return t;
    }
}
