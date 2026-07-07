package com.poc.taskengine.dto;

import java.time.Instant;

/**
 * Uniform error response shape returned by GlobalExceptionHandler for every
 * non-2xx response.
 *
 * Design decision — why a dedicated ErrorResponse class and not Spring's
 * default ProblemDetail (RFC 7807)?
 *   ProblemDetail is available in Spring 6 / Boot 3 and is a valid choice, but
 *   it requires the caller to understand the RFC 7807 "type" URI convention.
 *   Our explicit ErrorResponse is simpler, gives the team full control over the
 *   field names, and is identical in shape to what most Java backend interviews
 *   and production codebases use. We can migrate to ProblemDetail in a later
 *   phase if required.
 *
 * Fields mirror what the Phase 3 spec requires:
 *   { timestamp, status, error, message, path }
 */
public class ErrorResponse {

    /**
     * When the error was generated — ISO-8601 UTC string via Jackson's
     * default Instant serialiser. Lets the caller correlate with server logs.
     */
    private final Instant timestamp;

    /**
     * Raw HTTP status code (e.g., 400, 404, 409, 500).
     */
    private final int status;

    /**
     * Short, machine-readable label for the HTTP status reason phrase
     * (e.g., "Bad Request", "Not Found").
     */
    private final String error;

    /**
     * Human-readable explanation of what went wrong — safe to surface to the
     * API caller. Must never include a stack trace.
     */
    private final String message;

    /**
     * The request URI that triggered the error, populated from
     * HttpServletRequest.getRequestURI() in the handler.
     */
    private final String path;

    public ErrorResponse(int status, String error, String message, String path) {
        this.timestamp = Instant.now();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }

    // ── Getters only — this DTO is write-once, read-many ─────────────────────

    public Instant getTimestamp() { return timestamp; }
    public int getStatus()        { return status; }
    public String getError()      { return error; }
    public String getMessage()    { return message; }
    public String getPath()       { return path; }
}
