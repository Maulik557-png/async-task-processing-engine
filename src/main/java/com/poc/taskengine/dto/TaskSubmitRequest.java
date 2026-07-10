package com.poc.taskengine.dto;

import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound DTO for the POST /api/v1/tasks endpoint.
 */
@Getter
@Setter
@Schema(description = "Payload required to submit a new task")
public class TaskSubmitRequest {

    @NotNull(message = "type is required")
    @Schema(description = "The type of task to submit", example = "DATA_EXPORT", requiredMode = Schema.RequiredMode.REQUIRED)
    private TaskType type;

    @Schema(description = "Queue execution priority for this task (defaults to NORMAL)", example = "NORMAL")
    private TaskPriority priority;

    @Schema(description = "Task payload parameters, typically a JSON string", example = "{\"format\": \"csv\"}")
    private String payload;

    @NotBlank(message = "submittedBy is required and must not be blank")
    @Schema(description = "Name/identifier of the user or system submitting this task", example = "test-system", requiredMode = Schema.RequiredMode.REQUIRED)
    private String submittedBy;

    @Min(value = 0, message = "maxRetries must be 0 or greater")
    @Schema(description = "Maximum execution retry attempts for transient errors (defaults to 3)", example = "3")
    private Integer maxRetries;

    @Schema(description = "Optional idempotency key to prevent duplicate processing of the same submit", example = "idemp-key-12345")
    private String idempotencyKey;
}
