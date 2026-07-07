package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.service.TaskStateManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Models the execution of a REPORT_GENERATION task as a composed
 * CompletableFuture pipeline with four stages.
 *
 * ── WHY CompletableFuture PIPELINE INSTEAD OF ONE BIG supplyAsync ────────────
 *
 * Option A (naive — what most people write first):
 *   CompletableFuture.supplyAsync(() -> {
 *       fetchData(); formatData(); generatePDF(); return storeResult();
 *   }, executor);
 *
 * Problems with Option A:
 *   1. ONE THREAD, START TO FINISH — the executor thread is occupied for the entire
 *      pipeline duration. While waiting for fetchData() (which might do an HTTP call),
 *      that thread sits idle but is unavailable to other tasks. Under load, this
 *      causes queue build-up even when threads are technically "free" between stages.
 *   2. ALL-OR-NOTHING ERRORS — if generatePDF() throws, you can't tell the retry
 *      logic whether to retry from the start or from the failed stage.
 *   3. NOT COMPOSABLE — you can't independently test fetchData and formatData, and
 *      you can't swap in a different PDF generator without touching the whole block.
 *
 * Option B (this class — thenApplyAsync per stage):
 *   CompletableFuture
 *     .supplyAsync(this::fetchData, pipelineExecutor)     // Stage 1
 *     .thenApplyAsync(this::formatData, pipelineExecutor) // Stage 2
 *     .thenApplyAsync(this::generatePDF, pipelineExecutor)// Stage 3
 *     .thenApply(this::storeResult)                       // Stage 4: synchronous
 *     .exceptionally(this::handleError);
 *
 * Benefits of Option B:
 *   1. THREAD RELEASE BETWEEN STAGES — after fetchData() returns, the thread is
 *      released back to the pool. thenApplyAsync re-queues formatData as a new
 *      Runnable. While formatData waits in the queue, other tasks can run.
 *   2. PER-STAGE ERROR HANDLING — exceptionally() can distinguish which stage failed.
 *   3. EXPLICIT EXECUTOR ON EVERY STAGE — each *Async call receives pipelineExecutor.
 *      If we omit the executor argument, Java defaults to ForkJoinPool.commonPool(),
 *      which is shared by every CompletableFuture in the JVM. Using the common pool
 *      for our I/O-bound work would starve computation-bound tasks and violate our
 *      "isolated, named, bounded pools" architectural rule.
 *
 * ── WHY A SEPARATE PIPELINE EXECUTOR (not the main taskExecutor) ─────────────
 *
 * THREAD POOL DEADLOCK RISK:
 *   If we reuse the main taskExecutor (5 threads) for pipeline stages, and all 5
 *   threads are each running a REPORT_GENERATION task, each blocked in join():
 *     Thread 1: running stage 1 → calls join() → waits for stage 2
 *     Thread 2: running stage 1 → calls join() → waits for stage 2
 *     ...all 5 threads blocked in join()...
 *     Stage 2 tasks queued in the pool → never run → deadlock.
 *
 *   The main taskExecutor dispatches the TOP-LEVEL worker.run() call.
 *   The pipeline stages run on a SEPARATE cached-thread-pool so they are never
 *   competing with the blocked worker threads for the same pool slots.
 *
 *   We pass the main taskExecutor IN for logging/context, but pipeline stages
 *   always use the dedicated PIPELINE_EXECUTOR.
 *
 * ── WHY .join() AT THE END ────────────────────────────────────────────────────
 * TaskWorker.run() is called by the main pool. We need run() to block until the
 * pipeline is done so TaskWorker's run() can return only after the task is terminal
 * (ensuring the Semaphore permit is released at the right time). join() blocks the
 * calling thread and re-throws any unchecked exception that escaped exceptionally().
 * Because the PIPELINE_EXECUTOR is separate from the main pool, join() does NOT
 * cause a deadlock — only the main pool thread is blocked, not the pipeline threads.
 */
@Slf4j
public class ReportGenerationPipeline {

    /**
     * Dedicated executor for pipeline stages.
     *
     * WHY a separate cached thread pool?
     *   Cached thread pools create threads on demand and reuse idle ones. Since
     *   REPORT_GENERATION stages are short I/O-bound operations, the pool stays
     *   small under normal load (1-3 threads per pipeline). We avoid the deadlock
     *   described above by keeping pipeline stages off the main taskExecutor.
     *
     * WHY newCachedThreadPool instead of newFixedThreadPool(N)?
     *   We don't know how many concurrent pipelines will run. A cached pool
     *   scales with demand without pre-allocating idle threads. In Phase 6, if
     *   we need bounded concurrency for pipeline stages, replace with a bounded pool.
     *
     * WHY static? One pool for all pipeline instances — not one per task.
     *   Creating a new thread pool per pipeline task would be wasteful and could
     *   exhaust OS thread limits under burst load.
     */
    private static final Executor PIPELINE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setName("pipeline-stage-" + t.getId());
        t.setDaemon(true); // daemon: OK because we join() and stages don't own resources
        return t;
    });

    /**
     * Whether to inject a deliberate failure in the generatePDF stage.
     * Set to true in tests to verify exceptionally() fires correctly.
     * In production this is always false.
     */
    public static volatile boolean INJECT_PDF_FAILURE = false;

    private final Task task;
    private final TaskStateManager stateManager;

    public ReportGenerationPipeline(Task task, TaskStateManager stateManager, Executor mainExecutor) {
        this.task = task;
        this.stateManager = stateManager;
        // mainExecutor intentionally unused for stages — stages run on PIPELINE_EXECUTOR.
        // It is accepted in the constructor signature for future monitoring/metrics hooks.
    }

    /**
     * Execute the 4-stage pipeline synchronously from the caller's perspective.
     * Internally the stages run on PIPELINE_EXECUTOR (not the main task pool),
     * preventing thread-pool deadlock.
     */
    public void execute() {
        String taskId = task.getTaskId();
        String threadAtStart = Thread.currentThread().getName();
        log.info("[{}] REPORT_GENERATION pipeline starting for task [{}]", threadAtStart, taskId);

        CompletableFuture
                .supplyAsync(this::fetchData, PIPELINE_EXECUTOR)
                .thenApplyAsync(this::processData, PIPELINE_EXECUTOR)
                .thenApplyAsync(this::formatOutput, PIPELINE_EXECUTOR)
                .thenApplyAsync(this::storeResult, PIPELINE_EXECUTOR)
                .exceptionally(this::handleError)
                .join();
    }

    // ── Stage 1: fetchData (~1s) ──────────────────────────────────────────────

    private String fetchData() {
        String thread = Thread.currentThread().getName();
        log.info("[{}] REPORT_GENERATION task [{}] — Stage 1/4: fetchData (expected ~1s)", thread, task.getTaskId());
        simulateWork(900, 1100);
        String rawData = "{\"rows\":42,\"source\":\"db\"}";
        log.info("[{}] REPORT_GENERATION task [{}] — Stage 1 complete: fetched {} bytes",
                thread, task.getTaskId(), rawData.length());
        return rawData;
    }

    // ── Stage 2: processData (~2s) ─────────────────────────────────────────────

    private String processData(String rawData) {
        String thread = Thread.currentThread().getName();
        log.info("[{}] REPORT_GENERATION task [{}] — Stage 2/4: processData (expected ~2s)",
                thread, task.getTaskId());
        simulateWork(1800, 2200);
        String processed = "{\"processed\":true,\"rows\":42}";
        log.info("[{}] REPORT_GENERATION task [{}] — Stage 2 complete", thread, task.getTaskId());
        return processed;
    }

    // ── Stage 3: formatOutput (~500ms) ──────────────────────────────────────────

    private String formatOutput(String processedData) {
        String thread = Thread.currentThread().getName();
        log.info("[{}] REPORT_GENERATION task [{}] — Stage 3/4: formatOutput (expected ~500ms)", thread, task.getTaskId());

        if (INJECT_PDF_FAILURE) {
            log.warn("[{}] REPORT_GENERATION task [{}] — deliberate PDF failure injected",
                    thread, task.getTaskId());
            throw new RuntimeException("Simulated PDF generation failure (test injection)");
        }

        simulateWork(400, 600);
        String pdfRef = "report-" + task.getTaskId() + ".pdf";
        log.info("[{}] REPORT_GENERATION task [{}] — Stage 3 complete: generated {}",
                thread, task.getTaskId(), pdfRef);
        return pdfRef;
    }

    // ── Stage 4: storeResult (~200ms) ──────────────────────────────────────────

    private String storeResult(String pdfRef) {
        String thread = Thread.currentThread().getName();
        log.info("[{}] REPORT_GENERATION task [{}] — Stage 4/4: storeResult (expected ~200ms, ref={})",
                thread, task.getTaskId(), pdfRef);

        simulateWork(150, 250);

        // Mutate non-status fields BEFORE transitionStatus.
        task.setResult("PDF available at: " + pdfRef);
        task.setCompletedAt(java.time.Instant.now());
        stateManager.transitionStatus(task.getTaskId(), TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED);

        log.info("[{}] REPORT_GENERATION task [{}] → COMPLETED", thread, task.getTaskId());
        return pdfRef;
    }

    // ── Error handler ─────────────────────────────────────────────────────────
    // Called only when any stage throws. exceptionally() does NOT execute on success path.

    private String handleError(Throwable ex) {
        String thread = Thread.currentThread().getName();
        Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
        log.error("[{}] REPORT_GENERATION task [{}] pipeline failed: {}",
                thread, task.getTaskId(), cause.getMessage(), cause);

        // Mutate non-status fields first.
        task.setErrorMessage("Pipeline failure: " + cause.getMessage());
        task.setCompletedAt(java.time.Instant.now());
        try {
            stateManager.transitionStatus(task.getTaskId(), TaskStatus.IN_PROGRESS, TaskStatus.FAILED);
        } catch (Exception saveEx) {
            log.warn("[{}] Task [{}] could not be marked FAILED after pipeline error: {}",
                    thread, task.getTaskId(), saveEx.getMessage());
        }
        return null;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private void simulateWork(long minMs, long maxMs) {
        try {
            long sleepMs = ThreadLocalRandom.current().nextLong(minMs, maxMs);
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Stage interrupted", e);
        }
    }
}

