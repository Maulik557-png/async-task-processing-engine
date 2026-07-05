package com.poc.taskengine.controller;

import com.poc.taskengine.dto.TaskMapper;
import com.poc.taskengine.dto.TaskResponse;
import com.poc.taskengine.dto.TaskSubmitRequest;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.service.TaskService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the task lifecycle API.
 *
 * URL prefix: /api/v1/tasks — versioned from the start so we can introduce
 * /api/v2 later without breaking existing clients.
 *
 * Architectural rules enforced here:
 * 1. The domain model (Task) never touches this class.
 *    All request bodies are deserialized into DTOs.
 *    All responses are built from DTOs via TaskMapper.
 * 2. Business logic stays in TaskService — the controller only translates
 *    HTTP concepts (status codes, headers) to/from service calls.
 * 3. No try/catch here — exceptions bubble to GlobalExceptionHandler which
 *    converts them to structured error responses. Keeping handlers out of
 *    the controller prevents duplicate error-handling code.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/tasks
    //
    // Submit a new task for asynchronous processing.
    //
    // WHY 202 Accepted and not 200 OK?
    //   200 OK means "the request has been fulfilled and the response body
    //   contains the result." Here the result (task execution) hasn't happened yet.
    //   202 Accepted is the semantically correct code defined by RFC 9110 §15.3.3:
    //   "The request has been accepted for processing, but the processing has not
    //   been completed." The task ID in the body is a "monitor URI" hint so the
    //   client knows where to poll. Using 200 here would mislead the client into
    //   thinking the work is done.
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<Map<String, String>> submitTask(
            @Valid @RequestBody TaskSubmitRequest request) {

        TaskMapper.ResolvedSubmitParams params = TaskMapper.toSubmitParams(request);

        String taskId = taskService.submitTask(
                params.type(),
                params.priority(),
                params.payload(),
                params.submittedBy(),
                params.maxRetries()
        );

        log.info("POST /api/v1/tasks → accepted taskId={}", taskId);

        // Return just the taskId so the client can start polling.
        // A Map<String,String> avoids creating a one-field wrapper DTO for a
        // single field; if we need more fields later we can promote it to a DTO.
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Map.of("taskId", taskId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/tasks/{id}
    //
    // Retrieve the full status and details for a single task.
    // Used by the polling client to check PENDING → IN_PROGRESS → COMPLETED.
    // TaskNotFoundException (404) is raised by the service and handled globally.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable("id") String taskId) {
        Task task = taskService.getTask(taskId);
        log.debug("GET /api/v1/tasks/{} → status={}", taskId, task.getStatus());
        return ResponseEntity.ok(TaskMapper.toResponse(task));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/tasks?status=PENDING
    //
    // List all tasks, with an optional ?status= filter.
    // The status param is bound to the TaskStatus enum by Spring's ConversionService
    // automatically. If an invalid value is supplied (e.g., ?status=FOOBAR),
    // Spring raises a MethodArgumentTypeMismatchException before this method runs,
    // which GlobalExceptionHandler converts to a structured 400.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<TaskResponse>> listTasks(
            @RequestParam(name = "status", required = false) TaskStatus status) {

        List<TaskResponse> responses = taskService.getAllTasks(status)
                .stream()
                .map(TaskMapper::toResponse)
                .toList();

        log.debug("GET /api/v1/tasks?status={} → {} result(s)", status, responses.size());
        return ResponseEntity.ok(responses);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /api/v1/tasks/{id}
    //
    // Cancel a task, but only if it is still PENDING.
    // Returns 204 No Content on success — there is no body to return because
    // the cancel operation is a command, not a query. RFC 9110 §15.3.5:
    // "A server SHOULD NOT generate a payload body in response to a successful
    // DELETE request."
    //
    // TaskNotFoundException → 404 (handled globally).
    // InvalidTaskStateException → 400 (handled globally).
    // ─────────────────────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelTask(@PathVariable("id") String taskId) {
        taskService.cancelTask(taskId);
        log.info("DELETE /api/v1/tasks/{} → CANCELLED", taskId);
        return ResponseEntity.noContent().build();
    }
}
