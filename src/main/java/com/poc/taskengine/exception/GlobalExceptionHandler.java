package com.poc.taskengine.exception;

import com.poc.taskengine.dto.ErrorResponse;
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
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {

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
