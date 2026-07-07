package com.poc.taskengine.controller;

import com.poc.taskengine.dto.MetricsResponse;
import com.poc.taskengine.service.MetricsRegistry;
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
public class MetricsController {

    private final MetricsRegistry metricsRegistry;

    public MetricsController(MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    /**
     * Get a snapshot of current system statistics and counters.
     */
    @GetMapping
    public ResponseEntity<MetricsResponse> getMetrics() {
        log.debug("GET /api/v1/metrics invoked");
        return ResponseEntity.ok(metricsRegistry.getMetricsSummary());
    }
}
