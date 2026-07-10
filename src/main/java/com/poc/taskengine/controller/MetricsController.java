package com.poc.taskengine.controller;

import com.poc.taskengine.dto.MetricsResponse;
import com.poc.taskengine.service.MetricsRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for engine operation metrics.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
@Tag(name = "Metrics API", description = "Endpoints for inspecting task execution and thread pool operational metrics")
public class MetricsController {

    private final MetricsRegistry metricsRegistry;

    /**
     * Get a snapshot of current system statistics and counters.
     */
    @GetMapping
    @Operation(
            summary = "Retrieve engine metrics snapshot",
            description = "Returns current queue depths, active threads count, and aggregates of task submission outcomes."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Metrics summary successfully retrieved",
            content = @Content(schema = @Schema(implementation = MetricsResponse.class))
    )
    public ResponseEntity<MetricsResponse> getMetrics() {
        log.debug("GET /api/v1/metrics invoked");
        return ResponseEntity.ok(metricsRegistry.getMetricsSummary());
    }
}
