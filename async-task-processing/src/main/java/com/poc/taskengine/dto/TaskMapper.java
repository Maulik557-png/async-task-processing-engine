package com.poc.taskengine.dto;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.model.Task;

/**
 * Stateless utility class that converts between the Task domain model and DTOs.
 *
 * Design decisions:
 *
 * 1. WHY A SEPARATE MAPPER CLASS instead of putting toResponse() on Task or
 *    TaskController?
 *    - Putting it on Task would leak knowledge of the DTO layer into the domain
 *      model, violating the separation of concerns rule.
 *    - Putting it on the controller mixes transport logic with routing logic.
 *    - A dedicated mapper is easy to test in isolation, easy to extend (add new
 *      fields in one place), and makes the "no domain model over REST" rule
 *      mechanically enforced: the controller literally cannot build a response
 *      without going through this class.
 *
 * 2. WHY NOT MapStruct or ModelMapper?
 *    - Both add annotation-processor or reflection overhead and a new dependency.
 *    - Our mapping is simple enough (field-by-field copy) that hand-rolling it
 *      is readable, zero-cost at runtime, and requires no additional tooling.
 *    - MapStruct would be the right call once we have 10+ DTOs with complex
 *      transformations; at that point this class would become the MapStruct
 *      @Mapper interface.
 *
 * 3. All methods are static — the class carries no state and needs no Spring
 *    lifecycle. Callers that need DI wiring should inject the class and call
 *    instance methods, or just use it statically.
 */
public final class TaskMapper {

    private TaskMapper() {
        // Utility class — not instantiable.
    }

    /**
     * Convert the Task domain model to the outbound TaskResponse DTO.
     *
     * Every field that exists on the domain model is explicitly mapped here.
     * If a new field is added to Task and not to this method, the compiler will
     * NOT warn you — so treat this method as the canonical field inventory.
     *
     * @param task the domain model; must not be null
     * @return a populated TaskResponse ready for HTTP serialisation
     */
    public static TaskResponse toResponse(Task task) {
        return TaskResponse.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .type(task.getType())
                .priority(task.getPriority())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .result(task.getResult())
                .errorMessage(task.getErrorMessage())
                .retryCount(task.getRetryCount())
                .build();
    }

    /**
     * Extract the fields needed to call TaskService.submitTask() from the
     * inbound TaskSubmitRequest.
     *
     * Default rules applied here (not in the controller, not in the service):
     *   - priority: defaults to NORMAL if the caller omitted it.
     *   - maxRetries: defaults to 3 if the caller omitted it.
     *
     * These defaults live in the mapper because they are a presentation-layer
     * concern — the service and domain model should not assume any default;
     * they should receive fully-resolved values.
     *
     * @param request the inbound DTO; must not be null
     * @return a ResolvedSubmitParams record carrying all resolved values
     */
    public static ResolvedSubmitParams toSubmitParams(TaskSubmitRequest request) {
        return new ResolvedSubmitParams(
                request.getType(),
                request.getPriority() != null ? request.getPriority() : TaskPriority.NORMAL,
                request.getPayload(),
                request.getSubmittedBy(),
                request.getMaxRetries() != null ? request.getMaxRetries() : 3
        );
    }

    /**
     * Value object carrying the resolved (defaults-applied) parameters for a
     * task submission. A Java record for maximum brevity and immutability.
     */
    public record ResolvedSubmitParams(
            com.poc.taskengine.enums.TaskType type,
            com.poc.taskengine.enums.TaskPriority priority,
            String payload,
            String submittedBy,
            int maxRetries
    ) {}
}
