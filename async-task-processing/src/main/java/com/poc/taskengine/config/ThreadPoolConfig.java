package com.poc.taskengine.config;

import com.poc.taskengine.worker.LoggingRejectedExecutionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Thread pool configuration for asynchronous task execution.
 *
 * ── PHASE 4 CHANGE: PriorityBlockingQueue ────────────────────────────────────
 * We now use a raw ThreadPoolExecutor instead of Spring's ThreadPoolTaskExecutor
 * because ThreadPoolTaskExecutor does not expose a constructor that accepts a
 * custom BlockingQueue — it always builds a LinkedBlockingQueue internally.
 * To supply a PriorityBlockingQueue we must construct ThreadPoolExecutor directly.
 *
 * TRADE-OFF: We lose Spring's @Async integration and some Actuator queue-depth
 * metrics automatically, but those are not needed in this phase. The lifecycle
 * (graceful shutdown) is handled below by registering a JVM shutdown hook.
 *
 * WHY PriorityBlockingQueue OVER LinkedBlockingQueue?
 *   LinkedBlockingQueue is strictly FIFO: a CRITICAL task submitted after 50 LOW
 *   tasks must wait for all 50 to dequeue before it can run, even if all workers
 *   are free. PriorityBlockingQueue is a min-heap: it dequeues the element with
 *   the smallest compareTo value first. Because PriorityTaskWrapper.compareTo
 *   returns Integer.compare(this.priorityValue, other.priorityValue), and
 *   CRITICAL=1 < LOW=4, CRITICAL tasks always float to the top of the heap.
 *
 * WHY PriorityBlockingQueue IS UNBOUNDED (and why that is safe here)?
 *   PriorityBlockingQueue does not accept a capacity limit — it grows as needed.
 *   This sounds dangerous, but our Semaphore(50) in TaskService acts as the
 *   effective capacity cap: at most 50 tasks can enter the system. The queue will
 *   never hold more than 50 - corePoolSize tasks at peak, so it is de facto bounded.
 *   The initialCapacity hint (64) merely pre-allocates backing array space to avoid
 *   resize operations during startup burst.
 *
 * WHY ThreadPoolExecutor.CallerRunsPolicy?
 *   Even with the Semaphore guard, we keep CallerRunsPolicy as a secondary safety
 *   net. If a bug bypasses the Semaphore (e.g., in a future test or admin path),
 *   CallerRunsPolicy provides natural backpressure rather than silently dropping tasks.
 *
 * The pool is named "taskExecutor" so @Qualifier("taskExecutor") in TaskService
 * still resolves correctly.
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * Returns the bean as {@code Executor} (the interface) rather than
     * {@code ThreadPoolExecutor} (the concrete class).  Service-layer callers
     * depend on the abstraction and never need to know which pool implementation
     * is behind it — this makes future swaps (e.g., virtual threads in Phase 9)
     * require zero changes in the service layer.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        // Initial capacity hint for the backing array — generous to avoid resize
        // during the burst submission workload in the stress test.
        // PriorityBlockingQueue<Runnable> is used; the Comparable implementation
        // is on PriorityTaskWrapper, so non-PriorityTaskWrapper Runnables submitted
        // directly would cause ClassCastException at runtime. All submissions must
        // go through TaskService.submitTask → PriorityTaskWrapper.
        PriorityBlockingQueue<Runnable> priorityQueue = new PriorityBlockingQueue<>(64);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                5,              // corePoolSize: 5 threads always alive
                10,             // maxPoolSize: up to 10 under queue pressure
                                // Note: with PriorityBlockingQueue (unbounded), the pool
                                // NEVER exceeds corePoolSize — ThreadPoolExecutor only
                                // spawns extra threads when the queue is full, and an
                                // unbounded queue is never full. maxPoolSize is therefore
                                // effectively equal to corePoolSize here. This is a known
                                // ThreadPoolExecutor quirk with unbounded queues.
                                // For our use case (Semaphore caps at 50, corePoolSize=5)
                                // this is intentional: we want exactly 5 named worker threads.
                60L, TimeUnit.SECONDS,  // keepAlive for threads above corePoolSize
                priorityQueue,
                r -> {
                    // Named thread factory: every thread is "task-worker-N".
                    // Named threads are identifiable in thread dumps, JVisualVM, and logs.
                    Thread t = new Thread(r);
                    t.setName("task-worker-" + t.getId());
                    t.setDaemon(false); // non-daemon so JVM waits for tasks on shutdown
                    return t;
                },
                // Phase 5: LoggingRejectedExecutionHandler replaces CallerRunsPolicy.
                // See LoggingRejectedExecutionHandler Javadoc for full policy comparison.
                new LoggingRejectedExecutionHandler()
        );

        // NOTE: JVM shutdown hook removed in Phase 5.
        // Graceful shutdown is now handled by ShutdownManager @PreDestroy,
        // which runs inside the Spring lifecycle with full bean access.
        // See ShutdownManager.java for the detailed shutdown sequence.

        return executor;
    }

    /**
     * Dedicated pool for INVOICE_PROCESSING tasks.
     *
     * <p>Configured with core=3, max=5 to allocate dedicated resources to critical
     * financial transactions and guarantee that bulk workloads cannot cause starvation.
     */
    @Bean(name = "criticalTaskExecutor")
    public Executor criticalTaskExecutor() {
        PriorityBlockingQueue<Runnable> priorityQueue = new PriorityBlockingQueue<>(64);
        return new ThreadPoolExecutor(
                3,              // corePoolSize: 3 threads always alive
                5,              // maxPoolSize: up to 5 under queue pressure
                60L, TimeUnit.SECONDS,
                priorityQueue,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("critical-worker-" + t.getId());
                    t.setDaemon(false);
                    return t;
                },
                new LoggingRejectedExecutionHandler()
        );
    }

    /**
     * Dedicated pool for bulk EMAIL_NOTIFICATION tasks.
     *
     * <p>Configured with core=10, max=20 to handle high-throughput background
     * notification bursts without impacting critical transactions.
     */
    @Bean(name = "bulkTaskExecutor")
    public Executor bulkTaskExecutor() {
        PriorityBlockingQueue<Runnable> priorityQueue = new PriorityBlockingQueue<>(64);
        return new ThreadPoolExecutor(
                10,             // corePoolSize: 10 threads always alive
                20,             // maxPoolSize: up to 20 under queue pressure
                60L, TimeUnit.SECONDS,
                priorityQueue,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("bulk-worker-" + t.getId());
                    t.setDaemon(false);
                    return t;
                },
                new LoggingRejectedExecutionHandler()
        );
    }
}

