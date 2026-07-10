package com.poc.taskengine;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class PostgresRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskService taskService;

    @Autowired
    private com.poc.taskengine.repository.TaskJpaRepository taskJpaRepository;

    @BeforeEach
    public void setUp() {
        // Clean up database tables for test isolation
        taskJpaRepository.deleteAll();
    }

    @Test
    public void testTargetedStatusUpdates() {
        String id = UUID.randomUUID().toString();
        Task task = Task.builder()
                .taskId(id)
                .type(TaskType.EMAIL_NOTIFICATION)
                .priority(TaskPriority.NORMAL)
                .status(TaskStatus.PENDING)
                .submittedBy("test-user")
                .createdAt(Instant.now())
                .build();
        taskRepository.save(task);

        // Transition 1: Update status and startedAt
        Instant startedAt = Instant.now();
        taskRepository.updateStatusAndStartedAt(id, TaskStatus.IN_PROGRESS, startedAt);

        Task fetched1 = taskRepository.findById(id).orElseThrow();
        assertThat(fetched1.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(fetched1.getStartedAt()).isNotNull();

        // Transition 2: Update status, completedAt, and result
        Instant completedAt = Instant.now();
        taskRepository.updateStatusAndCompletedSuccess(id, TaskStatus.COMPLETED, completedAt, "completed-successfully");

        Task fetched2 = taskRepository.findById(id).orElseThrow();
        assertThat(fetched2.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(fetched2.getCompletedAt()).isNotNull();
        assertThat(fetched2.getResult()).isEqualTo("completed-successfully");
    }

    @Test
    public void testIdempotencyRaceCondition() throws Exception {
        String idempotencyKey = "idemp-key-" + UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Submit concurrently
        CompletableFuture<String> submit1 = CompletableFuture.supplyAsync(() ->
                taskService.submitTask(TaskType.EMAIL_NOTIFICATION, TaskPriority.NORMAL,
                        "{}", "submit-user", 3, UUID.randomUUID().toString(), idempotencyKey), executor);

        CompletableFuture<String> submit2 = CompletableFuture.supplyAsync(() ->
                taskService.submitTask(TaskType.EMAIL_NOTIFICATION, TaskPriority.NORMAL,
                        "{}", "submit-user", 3, UUID.randomUUID().toString(), idempotencyKey), executor);

        // Block until both finish
        CompletableFuture.allOf(submit1, submit2).join();

        String taskId1 = submit1.get();
        String taskId2 = submit2.get();

        executor.shutdown();

        // Assertions
        assertThat(taskId1).isEqualTo(taskId2); // Both callers must receive the exact same task ID

        // Query database to ensure only 1 row exists
        Optional<Task> dbTask = taskRepository.findByIdempotencyKey(idempotencyKey);
        assertThat(dbTask).isPresent();
        assertThat(dbTask.get().getTaskId()).isEqualTo(taskId1);

        // Make sure there are no other tasks with the same key
        List<Task> allTasks = taskRepository.findAll();
        long matches = allTasks.stream()
                .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
                .count();
        assertThat(matches).isEqualTo(1L);
    }
}
