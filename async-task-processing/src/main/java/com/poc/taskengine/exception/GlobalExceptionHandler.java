package com.poc.taskengine.exception;

import com.poc.taskengine.dto.ErrorResponse;
import com.poc.taskengine.exception.TaskQueueFullException;
import com.poc.taskengine.exception.TaskSubmissionRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Centralised exception handler — converts every exception thrown by any
 * @RestController into a structured ErrorResponse.
 *
 * WHY @RestControllerAdvice instead of @ControllerAdvice?
 *   @RestControllerAdvice = @ControllerAdvice + @ResponseBody on every handler.
 *   Because every method in this class returns a JSON body (not a View), using
 *   @RestControllerAdvice removes the need to annotate each @ExceptionHandler
 *   with @ResponseBody individually.
 *
 * WHY centralised handling instead of try/catch in each controller?
 *   1. DRY: the same 5-field error shape is produced for every endpoint without
 *      copy-pasting error-building code.
 *   2. Completeness: an @ExceptionHandler here catches exceptions from any
 *      controller in the application, including ones added in future phases.
 *   3. Separation of concerns: the controller handles routing; this class
 *      handles error translation. Neither knows about the other's internals.
 *
 * Exception coverage — ordered from most specific to least specific:
 *   TaskNotFoundException         → 404 Not Found
 *   TaskAlreadyExistsException    → 409 Conflict
 *   InvalidTaskStateException     → 400 Bad Request
 *   TaskQueueFullException        → 503 Service Unavailable
 *   MethodArgumentNotValidException → 400 Bad Request (Bean Validation failures)
 *   MethodArgumentTypeMismatchException → 400 Bad Request (enum parse failures)
 *   Exception (catch-all)         → 500 Internal Server Error
 *
 * No handler method in this class ever exposes a stack trace to the caller.
 * The stack trace is logged here (server-side) for debugging but stripped from
 * the HTTP response body.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ─── 503 Service Unavailable — rate limit reached ─────────────────────────
    //
    // WHY 503 and not 429 Too Many Requests?
    //   429 implies the CLIENT is sending too fast and should slow down.
    //   503 implies the SERVER is temporarily at capacity. Our limit is on
    //   in-flight task slots (a server resource), not per-client rate.
    //
    // WHY Retry-After: 5?
    //   A reasonable hint: tasks typically complete in 0.5–2.5 s; after 5 s
    //   at least some slots should have freed. The client is encouraged to retry
    //   rather than give up. RFC 7231 defines Retry-After as advisory.

    @ExceptionHandler(TaskQueueFullException.class)
    public ResponseEntity<ErrorResponse> handleQueueFull(
            TaskQueueFullException ex,
            HttpServletRequest request) {

        log.warn("Task queue full — returning 503: path={}", request.getRequestURI());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(new ErrorResponse(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "Service Unavailable",
                        ex.getMessage(),
                        request.getRequestURI()));
    }

    // ─── 503 Service Unavailable — executor rejected task (shutdown or overflow) ─
    //
    // Distinct from TaskQueueFullException (Semaphore gate, pre-persistence):
    //   TaskQueueFullException: Semaphore.tryAcquire() failed — too many in-flight tasks.
    //   TaskSubmissionRejectedException: executor.execute() was rejected — pool shut down
    //     or the rare queue-overflow path that bypasses the Semaphore.

    @ExceptionHandler(TaskSubmissionRejectedException.class)
    public ResponseEntity<ErrorResponse> handleSubmissionRejected(
            TaskSubmissionRejectedException ex,
            HttpServletRequest request) {

        log.warn("Task submission rejected by executor — returning 503: path={}", request.getRequestURI());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "10");

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(new ErrorResponse(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "Service Unavailable",
                        ex.getMessage(),
                        request.getRequestURI()));
    }

    // ─── 404 Not Found ────────────────────────────────────────────────────────

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTaskNotFound(
            TaskNotFoundException ex, HttpServletRequest request) {

        log.warn("Task not found: taskId={}, path={}", ex.getTaskId(), request.getRequestURI());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(
                        HttpStatus.NOT_FOUND.value(),
                        "Not Found",
                        ex.getMessage(),
                        request.getRequestURI()));
    }

    // ─── 409 Conflict ─────────────────────────────────────────────────────────

    @ExceptionHandler(TaskAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleTaskAlreadyExists(
            TaskAlreadyExistsException ex, HttpServletRequest request) {

        log.warn("Task already exists: taskId={}, path={}", ex.getTaskId(), request.getRequestURI());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(
                        HttpStatus.CONFLICT.value(),
                        "Conflict",
                        ex.getMessage(),
                        request.getRequestURI()));
    }

    // ─── 400 Bad Request — invalid state transition ───────────────────────────

    @ExceptionHandler(InvalidTaskStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTaskState(
            InvalidTaskStateException ex, HttpServletRequest request) {

        log.warn("Invalid state transition: taskId={}, currentStatus={}, path={}",
                ex.getTaskId(), ex.getCurrentStatus(), request.getRequestURI());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        "Bad Request",
                        ex.getMessage(),
                        request.getRequestURI()));
    }

    // ─── 400 Bad Request — malformed JSON body ────────────────────────────────
    //
    // Triggered when the request body is syntactically invalid JSON (e.g., missing
    // quotes, trailing commas). Jackson throws JsonParseException, which Spring wraps
    // in HttpMessageNotReadableException before it reaches any handler.
    // Without this explicit handler the exception falls through to the 500 catch-all,
    // which would mislead the caller into thinking it's a server fault.

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        log.warn("Malformed request body: path={}, cause={}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        "Bad Request",
                        "Request body is missing or malformed. Please provide valid JSON.",
                        request.getRequestURI()));
    }

    // ─── 400 Bad Request — Bean Validation failure (@Valid) ───────────────────

    //
    // Triggered by @Valid on the controller method parameter when a constraint
    // annotation (@NotNull, @NotBlank, @Min) is violated.
    // We collect all field errors into a single comma-separated message so the
    // caller can fix all problems in one round trip.

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationFailure(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : error.getField() + " is invalid")
                .collect(Collectors.joining("; "));

        log.warn("Validation failed: path={}, errors=[{}]", request.getRequestURI(), message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        "Bad Request",
                        message,
                        request.getRequestURI()));
    }

    // ─── 400 Bad Request — enum/type conversion failure ───────────────────────
    //
    // Triggered when a @RequestParam or @PathVariable cannot be converted to
    // its declared type (e.g., ?status=FOOBAR when TaskStatus enum has no FOOBAR).
    // Without this handler, Spring returns its default HTML error page.

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String message = String.format(
                "Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());

        log.warn("Type mismatch: path={}, {}", request.getRequestURI(), message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        "Bad Request",
                        message,
                        request.getRequestURI()));
    }

    // ─── 500 Internal Server Error — catch-all ────────────────────────────────
    //
    // The final safety net. Logs the full stack trace server-side but never
    // lets it reach the client response body. "Something went wrong" is
    // intentionally vague — leaking internal error details is a security risk.

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {

        // Log at ERROR level with full stack trace — this is the one place where
        // we WANT the trace, because unexpected exceptions need to be diagnosed.
        log.error("Unexpected error: path={}", request.getRequestURI(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error",
                        "An unexpected error occurred. Please contact support.",
                        request.getRequestURI()));
    }
}
