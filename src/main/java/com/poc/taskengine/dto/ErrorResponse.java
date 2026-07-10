package com.poc.taskengine.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import java.time.Instant;

/**
 * Uniform error response shape returned by GlobalExceptionHandler for every
 * non-2xx response.
 */
@Getter
@Schema(description = "Standard structure returned when an API request fails")
public class ErrorResponse {

    @Schema(description = "Timestamp when the error occurred", example = "2026-07-09T14:45:15.641Z")
    private final Instant timestamp;

    @Schema(description = "HTTP status code of the error", example = "400")
    private final int status;

    @Schema(description = "Short name of the HTTP error type", example = "Bad Request")
    private final String error;

    @Schema(description = "Detail message explaining the failure reason", example = "submittedBy is required and must not be blank")
    private final String message;

    @Schema(description = "Request path that triggered the error", example = "/api/v1/tasks")
    private final String path;

    public ErrorResponse(int status, String error, String message, String path) {
        this.timestamp = Instant.now();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }
}
