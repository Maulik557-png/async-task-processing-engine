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
 * Phase 2 verification harness — kept for historical reference only.
 *
 * DEACTIVATED in Phase 3: the REST layer now provides the canonical submission
 * path. The @Profile("phase2-only") annotation means this runner will NEVER fire
 * during normal startup or tests (no Spring profile named "phase2-only" is ever
 * activated). This preserves the file for code-review purposes without polluting
 * the Phase 3 manual curl verification with pre-submitted tasks.
 *
 * If you need to re-run Phase 2's concurrent submission test, start the app
 * with --spring.profiles.active=phase2-only.
 */
@Slf4j
@Configuration
@Profile("phase2-only")
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
                        "phase2-runner",
                        3   // default maxRetries
                );
                log.info(">>> Submitted task #{}: id={} type={} priority={}", i, taskId, type, priority);
            }

            log.info("=== All 10 tasks submitted. Watch the task-worker-* threads below. ===");
        };
    }
}
