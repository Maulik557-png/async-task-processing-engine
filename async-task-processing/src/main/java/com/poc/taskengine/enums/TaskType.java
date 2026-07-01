package com.poc.taskengine.enums;

/**
 * The category of work a task represents.
 *
 * TaskType drives two decisions in later phases:
 *   1. Which named, isolated thread pool executes the task (Phase 2).
 *   2. Which Worker implementation handles the execution (Phase 2).
 *
 * Adding a new task type here will require a matching pool and worker —
 * that coupling is explicit and intentional, not accidental.
 */
public enum TaskType {

    /**
     * Send an email notification (typically fast, I/O-bound).
     * Expected worker: EmailNotificationWorker
     */
    EMAIL_NOTIFICATION,

    /**
     * Generate a PDF or data report (CPU-bound, potentially long-running).
     * Expected worker: ReportGenerationWorker
     */
    REPORT_GENERATION,

    /**
     * Parse and process an invoice document (I/O-bound + external service calls).
     * Expected worker: InvoiceProcessingWorker
     */
    INVOICE_PROCESSING,

    /**
     * Export a large dataset to a file or external system (I/O-bound, slow).
     * Expected worker: DataExportWorker
     */
    DATA_EXPORT
}
