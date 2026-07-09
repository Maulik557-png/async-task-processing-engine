package com.poc.taskengine;

import com.poc.taskengine.dto.MetricsResponse;
import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.model.TaskAuditEvent;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.service.MetricsRegistry;
import com.poc.taskengine.service.TaskService;
import com.poc.taskengine.worker.TaskWorker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class Phase7IntegrationTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private MetricsRegistry metricsRegistry;

    @Autowired
    private Environment environment;

    @BeforeEach
    void cleanTaskWorkerHooks() {
        TaskWorker.forceFailCounts.clear();
    }

    // ─── Test 1: MDC Logging Verification ─────────────────────────────────────

    @Test
    @DisplayName("7.4 — MDC Pattern: properties verify taskId and taskType console outputs are configured")
    void mdc_consolePattern_configuredInEnvironment() {
        String logPattern = environment.getProperty("logging.pattern.console");
        assertThat(logPattern)
                .as("Console logging pattern must be configured and contain MDC placeholders")
                .isNotNull()
                .contains("%X{taskId}")
                .contains("%X{taskType}");
    }

    // ─── Test 2: Metrics Validation ───────────────────────────────────────────

    @Test
    @DisplayName("7.5 — Metrics Endpoint: submits tasks and verifies Atomic counters increment correctly")
    void metrics_accurateAndRealtime() throws Exception {
        // Capture baseline metrics
        MetricsResponse baseline = metricsRegistry.getMetricsSummary();

        // Submit 2 successful DATA_EXPORT tasks
        String taskId1 = taskService.submitTask(
                TaskType.DATA_EXPORT, TaskPriority.NORMAL, "{}", "metrics-test", 0);
        String taskId2 = taskService.submitTask(
                TaskType.DATA_EXPORT, TaskPriority.NORMAL, "{}", "metrics-test", 0);

        // Submit 1 failed EMAIL_NOTIFICATION task (fail 1/1, maxRetries=0)
        String failedTaskId = UUID.randomUUID().toString();
        TaskWorker.forceFailCounts.put(failedTaskId, new AtomicInteger(1));
        taskService.submitTask(
                TaskType.EMAIL_NOTIFICATION, TaskPriority.NORMAL, "{}", "metrics-test", 0, failedTaskId);

        // Wait for tasks to reach terminal states
        waitForTerminal(taskId1, 5000);
        waitForTerminal(taskId2, 5000);
        waitForTerminal(failedTaskId, 5000);

        // Read metrics summary
        MetricsResponse summary = metricsRegistry.getMetricsSummary();

        assertThat(summary.totalSubmitted())
                .as("totalSubmitted must increase by 3")
                .isEqualTo(baseline.totalSubmitted() + 3);

        assertThat(summary.totalCompleted())
                .as("totalCompleted must increase by 2")
                .isEqualTo(baseline.totalCompleted() + 2);

        assertThat(summary.totalFailed())
                .as("totalFailed must increase by 1")
                .isEqualTo(baseline.totalFailed() + 1);

        assertThat(summary.averageExecutionTimeMs())
                .as("averageExecutionTimeMs must be greater than 0 since tasks completed")
                .isGreaterThan(0.0);

        // Verify active tasks counters match live repository stats
        long pendingCount = taskRepository.findByStatus(TaskStatus.PENDING).size();
        assertThat(summary.tasksByStatus().get("PENDING"))
                .isEqualTo(pendingCount);
    }

    // ─── Test 3: Task Audit Trail ─────────────────────────────────────────────

    @Test
    @DisplayName("7.6 — Task Audit Trail: captures all status transitions chronologically through a retry loop")
    void auditTrail_capturesTransitionHistory() throws Exception {
        String taskId = UUID.randomUUID().toString();

        // Configure task to fail once, retry once, and then succeed on the second attempt
        TaskWorker.forceFailCounts.put(taskId, new AtomicInteger(1));

        // Submit with maxRetries = 1 (attempt 1: fail, attempt 2: succeed)
        taskService.submitTask(
                TaskType.DATA_EXPORT, TaskPriority.NORMAL,
                "{\"file\":\"audit-test.csv\"}", "audit-test", 1, taskId);

        // Wait for final completion (under retry rules)
        Task completedTask = waitForTerminal(taskId, 10_000);
        assertThat(completedTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);

        // Retrieve audit trail from task
        List<TaskAuditEvent> auditTrail = completedTask.getAuditTrail();
        assertThat(auditTrail)
                .as("Audit trail must capture all 5 state transitions")
                .hasSize(5);

        // Transition 1: PENDING -> IN_PROGRESS
        TaskAuditEvent event1 = auditTrail.get(0);
        assertThat(event1.getFromStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(event1.getToStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(event1.getThreadName()).contains("task-worker-");
        assertThat(event1.getMessage()).containsIgnoringCase("Transitioned from PENDING to IN_PROGRESS");

        // Transition 2: IN_PROGRESS -> FAILED
        TaskAuditEvent event2 = auditTrail.get(1);
        assertThat(event2.getFromStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(event2.getToStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(event2.getMessage()).containsIgnoringCase("Forced failure for retry test");

        // Transition 3: FAILED -> PENDING (Retry Scheduler)
        TaskAuditEvent event3 = auditTrail.get(2);
        assertThat(event3.getFromStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(event3.getToStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(event3.getThreadName()).contains("retry-scheduler-");
        assertThat(event3.getMessage()).containsIgnoringCase("Retry granted");

        // Transition 4: PENDING -> IN_PROGRESS
        TaskAuditEvent event4 = auditTrail.get(3);
        assertThat(event4.getFromStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(event4.getToStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(event4.getThreadName()).contains("task-worker-");

        // Transition 5: IN_PROGRESS -> COMPLETED
        TaskAuditEvent event5 = auditTrail.get(4);
        assertThat(event5.getFromStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(event5.getToStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(event5.getThreadName()).contains("task-worker-");

        // Verify timestamps are in chronological order
        for (int i = 0; i < auditTrail.size() - 1; i++) {
            assertThat(auditTrail.get(i).getTimestamp())
                    .isBeforeOrEqualTo(auditTrail.get(i + 1).getTimestamp());
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private Task waitForTerminal(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findById(taskId).orElseThrow();
            if (t.getStatus() == TaskStatus.COMPLETED ||
               (t.getStatus() == TaskStatus.FAILED && t.getRetryCount() >= t.getMaxRetries())) {
                return t;
            }
            Thread.sleep(100);
        }
        return taskRepository.findById(taskId).orElseThrow();
    }
}
