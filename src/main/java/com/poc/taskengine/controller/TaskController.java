package com.poc.taskengine.controller;

import com.poc.taskengine.dto.ErrorResponse;
import com.poc.taskengine.dto.TaskMapper;
import com.poc.taskengine.dto.TaskResponse;
import com.poc.taskengine.dto.TaskSubmitRequest;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for the task lifecycle API.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Tag(name = "Tasks API", description = "Endpoints for task submission, polling, listing, and cancellation")
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    @Operation(
            summary = "Submit a new task",
            description = "Submits a task for asynchronous background execution. Returns 202 Accepted with a task ID. If the submission queue is full, returns 503."
    )
    @ApiResponse(
            responseCode = "202",
            description = "Task successfully accepted for background execution",
            content = @Content(schema = @Schema(example = "{\"taskId\": \"e04df2e9-ef12-421b-873b-fde5bc632a4e\"}"))
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid validation constraints or payload mapping errors",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
    )
    @ApiResponse(
            responseCode = "503",
            description = "Execution queue is full. Try again later.",
            headers = @Header(name = "Retry-After", description = "Seconds client must wait before retrying", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
    )
    public ResponseEntity<Map<String, String>> submitTask(
            @Valid @RequestBody TaskSubmitRequest request) {

        TaskMapper.ResolvedSubmitParams params = TaskMapper.toSubmitParams(request);

        String taskId = taskService.submitTask(
                params.type(),
                params.priority(),
                params.payload(),
                params.submittedBy(),
                params.maxRetries(),
                UUID.randomUUID().toString(),
                params.idempotencyKey()
        );

        log.info("POST /api/v1/tasks → accepted taskId={}", taskId);

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Map.of("taskId", taskId));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Retrieve task details and execution status",
            description = "Returns the status, outcome, metadata, and lifecycle transition logs (audit trail) for a given task ID."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Task details successfully retrieved",
            content = @Content(schema = @Schema(implementation = TaskResponse.class))
    )
    @ApiResponse(
            responseCode = "404",
            description = "Task with the given ID was not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
    )
    public ResponseEntity<TaskResponse> getTask(
            @Parameter(description = "UUID of the task to query", example = "e04df2e9-ef12-421b-873b-fde5bc632a4e")
            @PathVariable("id") String taskId) {
        Task task = taskService.getTask(taskId);
        log.debug("GET /api/v1/tasks/{} → status={}", taskId, task.getStatus());
        return ResponseEntity.ok(TaskMapper.toResponse(task));
    }

    @GetMapping
    @Operation(
            summary = "List all tasks",
            description = "Returns a list of all tasks. Supports optional filtering by lifecycle status."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Successfully listed tasks",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaskResponse.class)))
    )
    public ResponseEntity<List<TaskResponse>> listTasks(
            @Parameter(description = "Optional status filter (PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, TIMED_OUT)")
            @RequestParam(name = "status", required = false) TaskStatus status) {

        List<TaskResponse> responses = taskService.getAllTasks(status)
                .stream()
                .map(TaskMapper::toResponse)
                .toList();

        log.debug("GET /api/v1/tasks?status={} → {} result(s)", status, responses.size());
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Cancel a pending task",
            description = "Attempts to cancel a task before it starts. This is only allowed if the task is currently in PENDING status."
    )
    @ApiResponse(
            responseCode = "204",
            description = "Task successfully cancelled (no response body)"
    )
    @ApiResponse(
            responseCode = "400",
            description = "Task is not in PENDING status and cannot be cancelled",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
    )
    @ApiResponse(
            responseCode = "404",
            description = "Task with the given ID was not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
    )
    public ResponseEntity<Void> cancelTask(
            @Parameter(description = "UUID of the task to cancel", example = "e04df2e9-ef12-421b-873b-fde5bc632a4e")
            @PathVariable("id") String taskId) {
        taskService.cancelTask(taskId);
        log.info("DELETE /api/v1/tasks/{} → CANCELLED", taskId);
        return ResponseEntity.noContent().build();
    }
}
