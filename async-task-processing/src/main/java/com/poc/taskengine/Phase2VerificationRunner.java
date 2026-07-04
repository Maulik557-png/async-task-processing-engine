package com.poc.taskengine;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Phase 2 verification harness — submits 10 tasks in rapid succession and
 * prints their IDs. The log output will show task-worker-1 through task-worker-5
 * picking up tasks concurrently (not sequentially).
 *
 * Annotated @Profile("!test") so this runner does NOT fire during unit tests,
 * avoiding side effects in future test phases.
 *
 * This class will be removed or replaced in Phase 3 when the REST endpoint
 * provides a proper submission path.
 */
@Slf4j
@Configuration
@Profile("!test")
public class Phase2VerificationRunner {

    @Bean
    public CommandLineRunner submitTenTasks(TaskService taskService) {
        return args -> {
            log.info("=== Phase 2 Verification: Submitting 10 tasks ===");

            TaskType[] types = TaskType.values();
            TaskPriority[] priorities = TaskPriority.values(); 

            for (int i = 1; i <= 10; i++) {
                TaskType type = types[i % types.length];
                TaskPriority priority = priorities[i % priorities.length];
                String taskId = taskService.submitTask(
                        type,
                        priority,
                        "{\"index\":" + i + "}",
                        "phase2-runner"
                );
                log.info(">>> Submitted task #{}: id={} type={} priority={}", i, taskId, type, priority);
            }

            log.info("=== All 10 tasks submitted. Watch the task-worker-* threads below. ===");
        };
    }
}
