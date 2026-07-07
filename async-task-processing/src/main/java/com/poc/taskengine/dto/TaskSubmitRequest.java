package com.poc.taskengine.dto;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Inbound DTO for the POST /api/v1/tasks endpoint.
 *
 * Architectural rule: this class is the only thing the controller accepts.
 * The Task domain model must never be deserialized directly from an HTTP body.
 * Mapping from this DTO to Task happens in TaskMapper, not in the controller.
 *
 * Validation annotations (@NotNull, @NotBlank, @Min) are evaluated by
 * Spring's @Valid mechanism before the controller method body runs.
 * A constraint violation produces a MethodArgumentNotValidException which
 * GlobalExceptionHandler converts to a structured 400 response.
 */
public class TaskSubmitRequest {

    /**
     * The category of work to perform.
     * Required — without it the engine cannot route the task to the right worker.
     */
    @NotNull(message = "type is required")
    private TaskType type;

    /**
     * Queue priority for this task.
     * Optional — defaults to NORMAL if omitted by the caller (applied in TaskMapper).
     * Not marked @NotNull deliberately: we want to allow the caller to omit it.
     */
    private TaskPriority priority;

    /**
     * Caller-supplied input, typically JSON-encoded.
     * Optional: some task types carry no input (e.g., a nightly report triggered by schedule).
     */
    private String payload;

    /**
     * Identity of the submitter — user ID, service name, or API key.
     * Required for audit trail: we must always know who submitted a task.
     */
    @NotBlank(message = "submittedBy is required and must not be blank")
    private String submittedBy;

    /**
     * Maximum number of automatic retries allowed before the task is marked
     * permanently FAILED. Must be zero or positive.
     * Optional — defaults to 3 if omitted (applied in TaskMapper).
     * Not marked @NotNull: zero is a valid explicit value (no retries).
     */
    @Min(value = 0, message = "maxRetries must be 0 or greater")
    private Integer maxRetries;

    // ── Getters and Setters ───────────────────────────────────────────────────
    // Plain getters/setters (no Lombok) so this class stays explicit and readable
    // without requiring the annotation processor at the DTO boundary.

    public TaskType getType()            { return type; }
    public void setType(TaskType type)   { this.type = type; }

    public TaskPriority getPriority()                   { return priority; }
    public void setPriority(TaskPriority priority)      { this.priority = priority; }

    public String getPayload()                { return payload; }
    public void setPayload(String payload)    { this.payload = payload; }

    public String getSubmittedBy()                   { return submittedBy; }
    public void setSubmittedBy(String submittedBy)   { this.submittedBy = submittedBy; }

    public Integer getMaxRetries()                   { return maxRetries; }
    public void setMaxRetries(Integer maxRetries)    { this.maxRetries = maxRetries; }
}
