package com.poc.taskengine;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.exception.InvalidTaskStateException;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskJpaRepository;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.service.TaskStateManager;
import com.poc.taskengine.service.TaskService;
import com.poc.taskengine.worker.PriorityTaskWrapper;
import com.poc.taskengine.worker.TaskHandler;
import com.poc.taskengine.worker.TaskHandlerRegistry;
import com.poc.taskengine.worker.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class ConcurrencyUnitTest extends BaseIntegrationTest {

    @Autowired
    private TaskStateManager stateManager;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskJpaRepository taskJpaRepository;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskHandlerRegistry handlerRegistry;

    private TaskHandler originalHandler;

    @BeforeEach
    public void setUp() {
        taskJpaRepository.deleteAll();
    }

    @AfterEach
    public void tearDown() {
        if (originalHandler != null) {
            handlerRegistry.registerHandler(TaskType.DATA_EXPORT, originalHandler);
        }
    }

    @Test
    public void testPriorityTaskWrapperComparator() {
        PriorityTaskWrapper critical = new PriorityTaskWrapper(() -> {}, TaskPriority.CRITICAL, "1");
        PriorityTaskWrapper high = new PriorityTaskWrapper(() -> {}, TaskPriority.HIGH, "2");
        PriorityTaskWrapper normal = new PriorityTaskWrapper(() -> {}, TaskPriority.NORMAL, "3");
        PriorityTaskWrapper low = new PriorityTaskWrapper(() -> {}, TaskPriority.LOW, "4");

        assertThat(critical.compareTo(low)).isLessThan(0);
        assertThat(low.compareTo(critical)).isGreaterThan(0);
        assertThat(high.compareTo(normal)).isLessThan(0);
        assertThat(normal.compareTo(normal)).isEqualTo(0);
    }

    @Test
    public void testTaskStateManagerConcurrencyRace() throws Exception {
        String taskId = UUID.randomUUID().toString();
        Task task = Task.builder()
                .taskId(taskId)
                .type(TaskType.DATA_EXPORT)
                .priority(TaskPriority.NORMAL)
                .status(TaskStatus.PENDING)
                .submittedBy("test-race")
                .createdAt(Instant.now())
                .build();
        taskRepository.save(task);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicReference<Exception> failureReason = new AtomicReference<>();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    stateManager.transitionStatusAndStartedAt(taskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS, Instant.now());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    failureReason.set(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
        assertThat(failureReason.get()).isInstanceOf(InvalidTaskStateException.class);
    }

    @Test
    public void testRetryLogicWithMockHandler() throws Exception {
        String taskId = UUID.randomUUID().toString();
        originalHandler = handlerRegistry.getHandler(TaskType.DATA_EXPORT);

        AtomicInteger attemptCounter = new AtomicInteger(0);
        TaskHandler mockHandler = new TaskHandler() {
            @Override
            public TaskType getSupportedType() {
                return TaskType.DATA_EXPORT;
            }

            @Override
            public TaskResult execute(Task task) {
                int attempt = attemptCounter.incrementAndGet();
                if (attempt < 3) {
                    throw new RuntimeException("Simulated failure attempt #" + attempt);
                }
                return new TaskResult("Success on attempt #" + attempt);
            }
        };

        handlerRegistry.registerHandler(TaskType.DATA_EXPORT, mockHandler);

        taskService.submitTask(TaskType.DATA_EXPORT, TaskPriority.NORMAL, "{}", "retry-test", 2, taskId, null);

        Task terminalTask = waitForTerminal(taskId, 15000);

        assertThat(terminalTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(terminalTask.getRetryCount()).isEqualTo(2);
        assertThat(attemptCounter.get()).isEqualTo(3);
    }

    private Task waitForTerminal(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findById(taskId).orElse(null);
            if (t != null && (t.getStatus() == TaskStatus.COMPLETED || t.getStatus() == TaskStatus.FAILED || t.getStatus() == TaskStatus.TIMED_OUT)) {
                if (t.getStatus() == TaskStatus.FAILED && t.getRetryCount() < t.getMaxRetries()) {
                    Thread.sleep(150);
                    continue;
                }
                return t;
            }
            Thread.sleep(100);
        }
        throw new RuntimeException("Task " + taskId + " did not reach terminal state in time");
    }
}
