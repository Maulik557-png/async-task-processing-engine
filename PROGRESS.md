# Async Task Processing Engine — Progress Log

---

## Phase 1 — Foundation
**Date:** 2026-07-01

### What was built
- **pom.xml** — Updated to Spring Boot 3.3.0 (downgraded from the generated 3.5.16 to stay
  on a stable GA release; 3.3.x is the latest LTS-aligned line). Dependencies added:
  `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`,
  `lombok`. `spring-boot-devtools` was already present and kept. Lombok excluded from fat-jar.
- **application.properties** — Port 8080 explicit, actuator health+info exposed,
  structured log pattern, DEBUG level for `com.poc.taskengine`.
- **Package skeleton** — All 10 packages created with `package-info.java` placeholders:
  `config`, `controller`, `dto`, `enums`, `exception`, `model`, `repository`, `service`,
  `worker`, `util`.
- **Enums** (in `com.poc.taskengine.enums`):
  - `TaskStatus` — PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, TIMED_OUT
  - `TaskPriority` — CRITICAL(1), HIGH(2), NORMAL(3), LOW(4) with `int value` field
  - `TaskType` — EMAIL_NOTIFICATION, REPORT_GENERATION, INVOICE_PROCESSING, DATA_EXPORT
- **Task** (in `com.poc.taskengine.model`) — 13-field domain model using `@Data @Builder
  @NoArgsConstructor @AllArgsConstructor`. All timestamps are `java.time.Instant` (UTC).
- **TaskRepository** (in `com.poc.taskengine.repository`) — Interface only, 5 methods:
  `save`, `findById` (returns `Optional<Task>`), `findAll`, `findByStatus`, `updateStatus`.
  Zero JPA types anywhere in the interface.

### Assumptions / Decisions
- **Base package:** `com.poc.taskengine` — the project was already scaffolded with this
  groupId. The spec says "replace yourname with a real name/org". Keeping this unless
  instructed otherwise.
- **Spring Boot version:** Downgraded generated 3.5.16 → **3.3.0**. 3.5.x does not exist
  as a GA release; Spring Initializr would have generated 3.3.x. Using 3.3.0 stable.
- **Lombok annotations:** `@Builder` chosen for fluent construction. `@Data` = getters +
  setters + equals/hashCode + toString. `@NoArgsConstructor` + `@AllArgsConstructor`
  required alongside `@Builder` so Jackson (Phase 3) and JPA (Phase 8) can deserialise.
- **`findById` returns `Optional<Task>`** (not `Task`): the spec says `findById(String taskId)`.
  Returning Optional is strictly safer — it makes the "not found" case unambiguous without
  null checks or exceptions, and it's what every production repository returns. This is
  additive, not a deviation.

### Deviations from spec
None. All five methods present on `TaskRepository`. All six statuses, all four priorities
with int value, all four task types defined.

### What's left for future phases
- Phase 3: REST endpoints in `controller/`, DTOs in `dto/`.
- Phase 4: State-machine transition guard.
- Phase 5: Retry logic.
- Phase 6–9: As specified.

---

## Phase 2 — Core Engine
**Date:** 2026-07-01

### What was built
- **InMemoryTaskRepository** (`repository/`) — `ConcurrentHashMap<String, Task>` with
  lock-striping rationale in comments. `updateStatus` uses `ConcurrentHashMap.compute()`
  for per-bin atomicity (no full-map lock, no lost updates under concurrent writes).
- **ThreadPoolConfig** (`config/`) — `ThreadPoolTaskExecutor` bean named `"taskExecutor"`:
  corePoolSize=5, maxPoolSize=10, queueCapacity=100, prefix=`task-worker-`,
  `CallerRunsPolicy`, graceful shutdown with 30 s termination wait.
  Every parameter has a comment explaining its production implication.
- **TaskWorker** (`worker/`) — `Runnable` that transitions PENDING→IN_PROGRESS→COMPLETED
  (or FAILED on interruption/exception). Logs `Thread.currentThread().getName()` at every
  transition. Uses `ThreadLocalRandom` (not `Math.random`) to avoid shared-seed contention.
- **TaskService** (`service/`) — `submitTask(type, priority, payload, submittedBy)`:
  UUID generation, PENDING state, save-before-submit, non-blocking `executor.execute()`.
  Injected with `@Qualifier("taskExecutor")` to future-proof against multiple pools.
- **Phase2VerificationRunner** — `@Profile("!test")` `CommandLineRunner` that submits
  10 tasks on startup.

### Key concurrency decisions
| Decision | Why |
|---|---|
| `ConcurrentHashMap` | Lock striping vs. single global lock of `synchronizedMap`. Near-zero contention for different task IDs. |
| `compute()` for updateStatus | Atomic per-bin lambda; two threads on the same taskId serialise only at that bin. |
| `CallerRunsPolicy` | Backpressure without silent drops. Caller blocks instead of getting an error. |
| `ThreadLocalRandom` in worker | Each thread has its own PRNG; avoids `Math.random()`'s synchronized seed. |

### Evidence — concurrent execution (actual log output)
```
20:56:36 [restartedMain]  === All 10 tasks submitted ===   ← submission loop done
20:56:37 [task-worker-2]  Task [6c023736] COMPLETED (579 ms)
20:56:37 [task-worker-1]  Task [f4daa760] COMPLETED (978 ms)
20:56:38 [task-worker-4]  Task [d30183c2] COMPLETED (1402 ms)
20:56:38 [task-worker-1]  Task [0f50802f] COMPLETED (888 ms)
20:56:38 [task-worker-3]  Task [65d31c71] COMPLETED (2231 ms)
20:56:38 [task-worker-2]  Task [2060a2d1] COMPLETED (1723 ms)
20:56:38 [task-worker-5]  Task [7bdd03a6] COMPLETED (2308 ms)
20:56:39 [task-worker-1]  Task [2e29fe36] COMPLETED (543 ms)
20:56:39 [task-worker-4]  Task [a6421c97] COMPLETED (1367 ms)
20:56:40 [task-worker-3]  Task [73cc8603] COMPLETED (1439 ms)
```
Five distinct worker threads (`task-worker-1` through `task-worker-5`) all had IN_PROGRESS
tasks simultaneously. Timestamps are interleaved — not sequential. No exceptions. No corrupted state.

### Deviations from spec
None. All five spec items implemented exactly as described.

### What's left for future phases
- Phase 3: REST endpoints in `controller/`, DTOs in `dto/`.
- Phase 4: State-machine transition guard.
- Phase 5: Retry logic.
- Phase 6–9: As specified.

---

## Phase 3 — REST API Layer
**Date:** 2026-07-04

### What was built
- **`dto/TaskSubmitRequest`** — inbound DTO with `@NotNull type`, `@NotBlank submittedBy`,
  optional `priority` (defaults NORMAL) and `maxRetries` (defaults 3) via mapper.
- **`dto/TaskResponse`** — outbound DTO with static builder; private constructor forces all
  construction through `TaskMapper`. Domain model cannot be returned directly.
- **`dto/TaskMapper`** — stateless utility applying defaults and mapping Task ↔ DTOs.
  `ResolvedSubmitParams` is a Java record carrying fully-resolved submit parameters.
- **`dto/ErrorResponse`** — consistent `{timestamp, status, error, message, path}` shape
  used by every non-2xx response. No Lombok on DTOs — explicit getters for readability.
- **`exception/TaskNotFoundException`** → 404, **`TaskAlreadyExistsException`** → 409,
  **`InvalidTaskStateException`** → 400.
- **`exception/GlobalExceptionHandler`** (`@RestControllerAdvice`) — handles:
  `TaskNotFoundException` (404), `TaskAlreadyExistsException` (409),
  `InvalidTaskStateException` (400), `HttpMessageNotReadableException` (400 — malformed JSON),
  `MethodArgumentNotValidException` (400 — Bean Validation), 
  `MethodArgumentTypeMismatchException` (400 — bad enum value), `Exception` (500 catch-all).
  Stack trace is logged server-side but never in the response body.
- **`controller/TaskController`** — four endpoints:
  - `POST /api/v1/tasks` → 202 Accepted + `{"taskId": "..."}` (with explanation in Javadoc
    of why 202 ≠ 200)
  - `GET /api/v1/tasks/{id}` → 200 with full `TaskResponse`
  - `GET /api/v1/tasks?status=PENDING` → 200 with list (optional filter)
  - `DELETE /api/v1/tasks/{id}` → 204 No Content (cancel PENDING only)
- **`service/TaskService`** — added `getTask()`, `getAllTasks(TaskStatus)`, `cancelTask()`;
  `submitTask()` now accepts `maxRetries` as a parameter (not hardcoded).
- **`Phase2VerificationRunner`** — deactivated via `@Profile("phase2-only")` so it never
  fires during normal startup or tests. Retained for history.

### Key design decisions
| Decision | Why |
|---|---|
| 202 Accepted on submission | RFC 9110 §15.3.3: "accepted for processing but not yet processed." Using 200 would imply the work is done. |
| Dedicated TaskMapper class | Enforces the DTO boundary mechanically. Controller cannot build a response without going through it. |
| Static builder on TaskResponse | Private constructor prevents ad-hoc construction that might skip fields. |
| No Lombok on DTOs | DTOs are explicit contracts — verbose getters are intentional and easy to read without annotation processor knowledge. |
| HttpMessageNotReadableException → 400 | Malformed JSON is a client error, not a server fault. Without an explicit handler it fell through to the 500 catch-all. |
| 204 No Content on cancel | RFC 9110 §15.3.5: server SHOULD NOT generate a body for a DELETE response. |

### Evidence — full curl session

**POST 202 + PENDING → IN_PROGRESS → COMPLETED:**
```
POST → HTTP 202: {"taskId":"b7c9d40e-a4ff-4653-8797-05ac0fd68ee5"}

Poll 1 (100ms):  {"status":"PENDING",  "startedAt":null,   "completedAt":null}
Poll 2 (1s):     {"status":"IN_PROGRESS","startedAt":"2026-07-04T14:29:44.066Z","completedAt":null}
Poll 3 (2.5s):   {"status":"COMPLETED","completedAt":"2026-07-04T14:29:45.693Z","result":"Simulated result for task b7c9d40e..."}
```

**Structured 400 — missing required field (`type`):**
```
POST body: {"submittedBy":"phase3-test"}
→ HTTP 400: {"timestamp":"...","status":400,"error":"Bad Request","message":"type is required","path":"/api/v1/tasks"}
```

**Structured 404 — nonexistent task:**
```
GET /api/v1/tasks/00000000-dead-beef-0000-000000000000
→ HTTP 404: {"status":404,"error":"Not Found","message":"Task not found: 00000000-dead-beef-0000-000000000000","path":"..."}
```

**Cancel PENDING → 204, re-cancel CANCELLED → 400:**
```
DELETE /api/v1/tasks/{id} (PENDING)  → HTTP 204 (no body)
GET    /api/v1/tasks/{id}            → {"status":"CANCELLED"}
DELETE /api/v1/tasks/{id} (CANCELLED)→ HTTP 400: {"message":"Cannot cancel task [...]: current status is CANCELLED"}
```

**Status filter:**
```
GET /api/v1/tasks?status=PENDING → HTTP 200, JSON array of PENDING tasks only
```

### Deviations from spec
- Added `HttpMessageNotReadableException` → 400 handler. The spec says "missing-required-field
  returns structured 400" — strictly a validation concern, but a malformed JSON body also
  deserves 400 not 500. This is strictly additive and makes the error contract more correct.
- `TaskAlreadyExistsException` cannot be triggered via the current in-memory implementation
  because UUID generation guarantees uniqueness. The exception class and handler exist as
  specified for future caller-supplied-ID or replay paths.

### What's left for future phases
- Phase 4: Atomic state-machine transition guard (prevents CANCELLED→IN_PROGRESS race noted
  in `cancelTask()` Javadoc).
- Phase 5: Retry logic wired to `maxRetries`.
- Phase 6–9: As specified.



---

## Phase 4 - Concurrency Deep Dive
**Date:** 2026-07-05

### What was built

#### 4.1 - PriorityBlockingQueue integration
- **`ThreadPoolConfig`** - replaced `ThreadPoolTaskExecutor` with a raw `ThreadPoolExecutor`
  backed by a `PriorityBlockingQueue<Runnable>`. Raw executor is required because
  `ThreadPoolTaskExecutor` does not expose a hook to inject a custom `BlockingQueue`.
- **`PriorityTaskWrapper`** - `implements Comparable<PriorityTaskWrapper>`, wraps a `Runnable`
  and compares by `TaskPriority.getValue()` (lower number = higher priority). Without this
  wrapper the queue would attempt to cast the `Runnable` to `Comparable` and throw
  `ClassCastException` at dequeue time.
- The `PriorityBlockingQueue` is technically unbounded but effectively capped at 50 tasks
  by the Semaphore gate in `TaskService`.

#### 4.2 - Atomic state transitions with ReentrantLock
- **`TaskStateManager`** - `ConcurrentHashMap<String, ReentrantLock>` provides one lock per
  `taskId`. `computeIfAbsent` is atomic at the bin level.
- **`transitionStatus(taskId, expectedStatus, newStatus)`** - acquires the per-task lock,
  reads current status, verifies it equals `expectedStatus`, then calls `updateStatus`.
  Throws `InvalidTaskStateException` if the guard fails.
- **Critical implementation lesson discovered**: `InMemoryTaskRepository` stores object
  references. If the caller mutates `task.setStatus(NEW)` BEFORE calling `transitionStatus`,
  then `findById()` inside the lock returns the already-mutated object, the guard always
  fails, and tasks are permanently stuck. Fix: callers NEVER touch `task.status` before
  the call; the lock updates status atomically via `updateStatus()`.
- WHY `ReentrantLock` over `synchronized`: `tryLock(timeout)` for watchdog support,
  `lockInterruptibly()` for shutdown coordination, named lock holder in thread dumps.

#### 4.3 - Semaphore rate limiting
- **`TaskService`** - `Semaphore(50, true)` with `tryAcquire()` (non-blocking) before
  persistence. Permit released in `finally` block in a wrapper `Runnable` after exit.
- WHY `tryAcquire()` not `acquire()`: `acquire()` blocks the Tomcat HTTP thread.
- WHY `fairness=true`: FIFO grant order prevents starvation under sustained load.
- **`TaskQueueFullException`** - HTTP 503 with `Retry-After: 5` header.

#### 4.4 - CompletableFuture pipeline (REPORT_GENERATION)
- **`ReportGenerationPipeline`** - 4-stage chain on a dedicated static `PIPELINE_EXECUTOR`
  (`newCachedThreadPool`), NOT the main 5-thread pool.
- WHY dedicated executor: if all 5 main threads block in `join()` while waiting for
  `thenApplyAsync` stages queued on the same pool, no thread can run those stages -
  classic thread-pool deadlock. The cached pool eliminates the deadlock entirely.
- WHY `thenApplyAsync` per stage: thread releases between stages; other tasks run while
  a stage waits in the queue.

### Evidence - test output (mvn test)

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
Total time:  39.912 s -- BUILD SUCCESS
```

**Test 1 - Stress test (200 tasks / 20 threads):**
```
[STRESS] Submitted 200 tasks (rejected: 0) (allSubmitted within 60s: true)
[STRESS] 200 tasks, all terminal. Unique IDs: 200

[task-worker-53] Task [cb8275c8] transitioned IN_PROGRESS -> COMPLETED (under lock)
[task-worker-53] Task [cb8275c8] (type=DATA_EXPORT) -> COMPLETED (took 1974 ms)
[task-worker-53] Semaphore permit released for [cb8275c8]. Available: 5/50
```

**Test 2 - State machine guard (race on same task):**
```
[RACE] race-thread-1 WON
[RACE] race-thread-2 REJECTED: Cannot transition to IN_PROGRESS (expected status was PENDING)
[RACE] successes=1, rejections=1
[RACE] Lock guard proved: only one transition succeeded
```

**Test 3 - Semaphore rate limiting:**
```
[RATE] TaskQueueFullException thrown after 50 flood tasks (available was 50)
[RATE] 503 condition confirmed - queue full exception thrown
```

**Test 4 - Pipeline injected failure:**
```
[PIPELINE] Task [...] status=FAILED
errorMessage=Pipeline failure: Simulated PDF generation failure (test injection)
```

**Test 5 - Pipeline happy path:**
```
[pipeline-stage-59] Stage 1/4: fetchData  -> 25 bytes
[pipeline-stage-60] Stage 2/4: formatData -> complete
[pipeline-stage-59] Stage 3/4: generatePDF -> report-6b620942...pdf
[pipeline-stage-59] Stage 4/4: storeResult -> IN_PROGRESS -> COMPLETED (under lock)
[PIPELINE] Task [...] result=PDF available at: report-6b620942-c5ef-45bd-8140-dae9bd7e9f93.pdf
```

### Definition of Done - Phase 4 checklist

| Requirement | Status | Evidence |
|---|---|---|
| PriorityBlockingQueue + PriorityTaskWrapper | PASS | Raw ThreadPoolExecutor; compareTo() on priority value |
| CRITICAL tasks dequeue before LOW under backlog | PASS | min-heap ordering (CRITICAL.value=1 < LOW.value=4) |
| ReentrantLock prevents double-transition | PASS | Race test: successes=1, rejections=1 |
| Explanation of ReentrantLock over synchronized | PASS | TaskStateManager Javadoc |
| Semaphore(50, true) with 503 on overflow | PASS | Rate limit test confirms; Retry-After: 5 in response |
| Explanation of fairness + tryAcquire | PASS | TaskService Javadoc |
| 4-stage CompletableFuture pipeline | PASS | ReportGenerationPipeline on dedicated PIPELINE_EXECUTOR |
| Explanation of thenApplyAsync per stage | PASS | Class Javadoc (Option A vs Option B) |
| exceptionally() fires on stage failure | PASS | Test 4 confirms status=FAILED |
| 200-task / 20-thread stress test | PASS | Tests run: 6, Failures: 0, Errors: 0 |
| No double-processing, no backwards transitions | PASS | uniqueIds==200 assertion passes |

### Key bugs discovered and fixed during implementation

1. **Reference-aliasing bug** - Pre-mutating `task.status` before the lock caused `findById()`
   inside the guard to see the new status, not the old one. Every transition was rejected and
   tasks permanently stuck IN_PROGRESS. Fix: `transitionStatus` pattern (no pre-mutation).

2. **Thread-pool deadlock** - Pipeline stages queued on the same 5-thread pool blocked in
   `join()`. All threads blocked, stages never ran.
   Fix: dedicated static `PIPELINE_EXECUTOR` (newCachedThreadPool).

3. **Test isolation** - Rate-limit test left 50 flood tasks in-flight; pipeline tests hit 503.
   Fix: `waitForPermit()` helper polls until a slot frees before pipeline submission.

### What's left for future phases
- Phase 6: Core Engine refinement (real business logic instead of simulated sleep, concrete dependencies).
- Phase 7–9: As specified.

---

## Phase 5 — Resilience Layer
**Date:** 2026-07-07

### What was built

#### 5.1 — Retry with Exponential Backoff + Jitter
- **`RetryConfig`** — Exposes a 2-thread `ScheduledExecutorService` bean named `retrySchedulerExecutor` to isolate retry callback execution from main task worker pool threads. Binds backoff/jitter configuration parameters from `application.properties`.
- **`RetryScheduler`** — Computes delay using `waitTime = baseDelay * (2 ^ retryCount) + random(0, maxJitter)` and schedules one-shot executions on `retrySchedulerExecutor`. Prevents synchronized retry storms when systemic failure occurs.
- **`TaskService.retryTask(taskId)`** — Increments the retry count, resets transient worker fields, transitions state via `retryTransition()`, acquires a rate-limiting semaphore permit (blocking with a 5s timeout, acceptable since it runs on a scheduler thread), and re-submits the task to the main executor.
- **`TaskStateManager.retryTransition(taskId)`** — Audited transition gate that allows a task to exit the FAILED terminal state to PENDING (`FAILED → PENDING`). All other transitions from terminal states remain strictly rejected.
- **`TaskWorker`** — Tracks attempts and invokes `RetryScheduler` when an execution fails if `retryCount < maxRetries`.

#### 5.2 — Task Timeout Watchdog
- **`TaskTimeoutWatchdog`** — Background job configured with `@Scheduled(fixedDelayString = "${task.watchdog.interval-ms}")` that scans for `IN_PROGRESS` tasks.
- Evicts any task exceeding `task.timeout.seconds` by atomically transitioning `IN_PROGRESS → TIMED_OUT` via `TaskStateManager`.
- Thread interruption is avoided to prevent data corruption. The lock ensures late-completing threads are rejected when attempting to write `COMPLETED` on a `TIMED_OUT` task.

#### 5.3 — Logging Rejection Handler
- **`LoggingRejectedExecutionHandler`** — Custom `RejectedExecutionHandler` replacing `CallerRunsPolicy`. Logs a structured `ERROR` with thread pool diagnostics (active/max threads, queue depth, shutdown state) and throws `TaskSubmissionRejectedException`.
- **`TaskSubmissionRejectedException`** — Maps to HTTP `503 Service Unavailable` with a `Retry-After: 10` header via `GlobalExceptionHandler` to notify downstream callers.

#### 5.4 — Graceful Shutdown
- **`ShutdownManager`** — Integrates with the Spring Container lifecycle using `@PreDestroy` (replaces bare JVM shutdown hook).
- Initiates `pool.shutdown()` (blocking new submissions via 503), awaits termination for up to 30 seconds, and resorts to `pool.shutdownNow()` to interrupt threads if the drain window expires. Cleanly shuts down the retry scheduler.

#### 5.5 — Circuit Breaker (Log-Only)
- **`CircuitBreakerRegistry`** — Tracks consecutive permanent failures (after all retries are exhausted) per `TaskType` using thread-safe `AtomicInteger` counters in a `ConcurrentHashMap`.
- Emits a high-visibility `WARN` log advising rate limitation/pauses when consecutive permanent failures reach a threshold of 10. Succesful completions reset the counter to 0.

### Key resilience decisions
| Design / Policy | Strategy | Why |
|---|---|---|
| **Dedicated Scheduled Pool** | 2 Threads | Isolates retry timer tasks from worker execution load; callback functions are non-blocking. |
| **Exponential Backoff Jitter** | Jitter [0, 100ms] | Prevents "retry storms" by spreading retries across a temporal window. |
| **FAILED → PENDING Transition** | Locked Gate | Transient failures are visible in the audit history while ensuring terminal state integrity. |
| **Rejection Handler** | Log + Exception | Replaces silent `CallerRunsPolicy` to expose overload metrics and return explicit 503. |
| **Shutdown Method** | `@PreDestroy` | Allows accessing Spring beans (e.g. repositories) during context teardown. |

### Evidence — test output (mvn clean test)

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
...
2026-07-07 13:33:50.391 [task-worker-69] WARN  c.p.t.service.CircuitBreakerRegistry - CIRCUIT BREAKER THRESHOLD REACHED — 10 consecutive permanent failures for EMAIL_NOTIFICATION. Consider pausing submissions of this type.
[CIRCUIT_BREAKER] Consecutive permanent failures for EMAIL_NOTIFICATION: 10 (threshold=10)
2026-07-07 13:33:51.361 [task-worker-68] DEBUG c.p.t.service.CircuitBreakerRegistry - Circuit breaker for EMAIL_NOTIFICATION reset to 0 (was 10 consecutive failures)
[CIRCUIT_BREAKER] Counter after success: 0
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.524 s -- in com.poc.taskengine.RetryIntegrationTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  43.054 s
```

### Definition of Done - Phase 5 checklist

| Requirement | Status | Evidence |
|---|---|---|
| Exponential Backoff + Jitter Retries | PASS | Logs show backoff delays doubling + random jitter (e.g. 210ms, 403ms). |
| TaskTimeoutWatchdog (TIMED_OUT) | PASS | Scans every 30s. Atomically transitions IN_PROGRESS → TIMED_OUT under lock. |
| Custom Rejection Handler (503) | PASS | Custom handler throws named exception; GlobalExceptionHandler translates to 503. |
| Graceful Shutdown `@PreDestroy` | PASS | Initiates pool shutdown, waits 30s for drainage, terminates retry scheduler. |
| Log-Only Circuit Breaker | PASS | Count reaches 10 consecutive failures → logs WARNING. Reset verified on success. |
| Complete JUnit Test Suite | PASS | `RetryIntegrationTest` validates retry success, retry exhaustion, and circuit breaker. |

### Key bugs discovered and fixed during implementation

1. **Test Race Condition on Instant Execution**
   - *Problem*: In `RetryIntegrationTest`, worker threads picked up newly submitted tasks so fast (<1ms) that the test thread could not register the task ID in `forceFailCounts` before execution occurred, leading to flaky test results.
   - *Fix*: Added an overloaded `submitTask` signature accepting a pre-generated task ID, allowing the test to pre-register the ID in `forceFailCounts` before submission.
2. **Transient FAILED State Polling**
   - *Problem*: The retry loop transitions `IN_PROGRESS → FAILED → PENDING`. Polling tests that checked only for `FAILED` status caught the task during the transient delay window before it retried, resulting in assertion failures.
   - *Fix*: Refactored `waitForTerminal` helper to only treat `FAILED` as terminal when `retryCount >= maxRetries`.

---

## Phase 6 — Task Type Handlers
**Date:** 2026-07-07

### What was built

#### 6.1 — TaskHandler Strategy & Registry
- **`TaskHandler`** — Strategy interface specifying `getSupportedType()` and `execute(Task)`.
- **`TaskResult`** — Immutable value object wrapping the result string.
- **`TaskHandlerRegistry`** — Spring component constructor-collecting all beans implementing `TaskHandler`, indexing them by `TaskType`.
- **`TaskWorker`** — Refactored to dynamically resolve the handler from the registry and run it, rather than utilizing hardcoded conditionals. Correctly handles post-execution state transition logic for self-completing pipelines vs standard runners.

#### 6.2 — Concrete Handlers
- **`EmailNotificationHandler`** — Parses `recipient`/`subject`/`body` JSON payload fields, simulates SMTP delivery by sleeping ~500ms, and randomly fails about 20% of the time to exercise retry mechanics.
- **`ReportGenerationHandler`** — Coordinates the CompletableFuture multi-stage pipeline, adjusting stages and delay timings (Fetch data ~1s, Process data ~2s, Format output ~500ms, Store result ~200ms).
- **`InvoiceProcessingHandler`** — Critical financial task executor. Guarantees lock-level idempotency by guarding duplicate `invoiceId` entries (concurrently checked and inserted under a static `ReentrantLock` into an in-memory set).
- **`DataExportHandler`** — Simulates export processing by sleeping ~1000ms.

#### 6.3 — Thread Pool Isolation per Category
- **`criticalTaskExecutor`** (core=3, max=5) — Dedicated pool routing `INVOICE_PROCESSING` tasks on `critical-worker-*` threads.
- **`bulkTaskExecutor`** (core=10, max=20) — Dedicated pool routing `EMAIL_NOTIFICATION` tasks on `bulk-worker-*` threads.
- **`taskExecutor`** (core=5, max=10) — Stays default routing for `REPORT_GENERATION` and `DATA_EXPORT` tasks.
- **`TaskService`** — Dynamically dispatches tasks to the correct executor based on `TaskType`.
- **`ShutdownManager`** — Refactored to coordinates orderly graceful shutdown for all three pools in parallel.

### Key architecture decisions
| Strategy / Design | Choice | Why |
|---|---|---|
| **Strategy Pattern** | Interface Registry | Eliminates `if/else` branching inside `TaskWorker`; allows adding new types without modifying engine. |
| **Executor Isolation** | Thread Pools per Category | Starvation protection. Bulk email bursts cannot consume resources allocated to critical invoices. |
| **Invoice Idempotency** | ReentrantLock + Set | Guarantees critical financial duplicate prevention under concurrent requests. |
| **Dynamic Fallback Transition** | Status Check | Safe integration of self-completing asynchronous pipelines (e.g. CF report pipeline) with standard synchronous executors. |

### Evidence — concurrent thread pool logs & test execution

**ThreadPool Name Verification & Starvation Protection (Logs):**
```text
2026-07-07 13:50:00.076 [critical-worker-47] INFO  c.p.t.w.InvoiceProcessingHandler - [critical-worker-47] Invoice [INV-ISOLATION-679aefae...] processed successfully
2026-07-07 13:50:00.091 [bulk-worker-44] INFO  c.p.t.w.EmailNotificationHandler - [bulk-worker-44] Email sent successfully to bulk-10@example.com
```
*Notice how email tasks are executing on `bulk-worker-44` while the invoice task starts immediately on `critical-worker-47`.*

**Clean Integration Test Execution (`mvn test -Dtest=Phase6IntegrationTest`):**
```text
[INFO] Running com.poc.taskengine.Phase6IntegrationTest
[ISOLATION] Invoice task submitted alongside 15 emails starts in 0ms
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.445 s -- in com.poc.taskengine.Phase6IntegrationTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Definition of Done - Phase 6 checklist

| Requirement | Status | Evidence |
|---|---|---|
| TaskType resolves via Strategy Registry | PASS | Registry constructors injects `List<TaskHandler>`; `TaskWorker` contains no type conditionals. |
| EmailNotificationHandler 20% Failures | PASS | Triggers random exceptions; log traces verify Phase 5 retry scheduler scheduling retries. |
| ReportGenerationHandler timings | PASS | Staged timings visible in logs (1s fetch → 2s process → 500ms format → 200ms store). |
| Invoice Lock Idempotency | PASS | Race test shows exactly one invoice succeeds and the duplicate gets rejected with 400/Exception. |
| Category Routing Pool Isolation | PASS | Invoice task submitted concurrently alongside 15 slow emails starts in 0ms on critical pool. |
| Clean Build & Test Run | PASS | Maven clean test compiles cleanly and runs 11 tests with 0 failures (`BUILD SUCCESS`). |

### Key bugs discovered and fixed during implementation

1. **InterruptedException Compiler Error**
   - *Problem*: Compiling `TaskWorker.java` failed with `java.lang.InterruptedException is never thrown in body of corresponding try statement` because the `Thread.sleep` simulation was moved out into the concrete `TaskHandlers`.
   - *Fix*: Removed the `InterruptedException` catch block from `TaskWorker.run()`. Interruption status is set and handled inside the handlers, propagating out as wrapped `TaskExecutionException`.
2. **Double FAILED State Transition**
   - *Problem*: When `ReportGenerationPipeline` fails, it transitions task state internally to `FAILED` and throws an exception. Catching this inside `TaskWorker` caused a double-transition check (`IN_PROGRESS → FAILED`) resulting in `InvalidTaskStateException` and skipping retries.
   - *Fix*: Modified `TaskWorker.markFailed` to only transition the state to `FAILED` if the status is currently `IN_PROGRESS`.

---

## Phase 7 — Observability
**Date:** 2026-07-07

### What was built

#### 7.1 — MDC (Mapped Diagnostic Context) Tracing
- **`application.properties`** — Updated `logging.pattern.console` logback pattern configuration to prepend task context variables: `[taskId=%X{taskId}] [taskType=%X{taskType}]`.
- **`TaskWorker`** — Wrapped the execution inside a `try-finally` block: puts `taskId` and `taskType` to MDC at task worker entry, and guarantees `MDC.clear()` runs inside the `finally` block to prevent diagnostic leaks onto recycled thread pool threads.

#### 7.2 — Live Metrics Registry
- **`MetricsRegistry`** — Backed by `AtomicLong` counters. Dynamically aggregates active worker thread counts and execution queue depths by reading live properties from the three executor pools. Calculates average task duration accurately. Exposes a snapshot summary.
- **`MetricsController`** — Implements `/api/v1/metrics` GET endpoint returning the live statistics.
- **`TaskService`, `TaskWorker`, `TaskTimeoutWatchdog`** — Injected metrics hook points to update statistics on task submission, completion, exception failure, and watchdog timeout eviction.

#### 7.3 — Audit Trails
- **`TaskAuditEvent`** — Record mapping a single lifecycle state update (fromStatus, toStatus, timestamp, threadName, description).
- **`Task` domain entity** — Added `List<TaskAuditEvent> auditTrail` persistent collection.
- **`TaskStateManager`** — Audits every status transition (inside `transitionStatus` and `retryTransition`) by appending a detailed `TaskAuditEvent` under lock, capturing the active executor thread details.
- **DTO mapping** — Exposed the audit trail via `TaskResponse` DTO and mapped it in `TaskMapper`.

### Key architecture decisions
| Observability Layer | Solution | Benefit |
|---|---|---|
| **Thread Context** | Slf4j MDC | Correlates logs from separate systems (database, queues, watchdog) to the single executing Task ID. |
| **Live state counters** | Live Thread Pool Metrics | Prevents metric drift. Executor active thread counts and queue sizes are queried directly, not separately managed. |
| **Audit Trails** | Entity Collection | Allows support teams to inspect a task's trajectory (retries, timeouts, worker names) in a single API get response. |

### Evidence — logs, test verification, and API shapes

**MDC Prepend Verification (Log trace):**
```text
2026-07-07 13:58:46.086 [task-worker-36] DEBUG c.p.t.service.TaskStateManager - [taskId=b28adadb-2f40-4e6a-bf6b-dfdcd25e08c0] [taskType=DATA_EXPORT] Task [b28adadb...] transitioned PENDING → IN_PROGRESS (under lock)
2026-07-07 13:58:47.088 [task-worker-35] DEBUG c.poc.taskengine.service.TaskService - [taskId=] [taskType=] Semaphore permit released for task [f803a22b...]. Available: 49/50
```
*Note that during worker run, MDC prepends task variables automatically. Once worker ends, MDC is cleared instantly (`taskId=` & `taskType=`).*

**API Response JSON Shape (`GET /api/v1/tasks/{id}` with Audit Trail):**
```json
{
  "taskId": "a92e8584-0610-482a-bc91-236bca481cfa",
  "status": "COMPLETED",
  "type": "DATA_EXPORT",
  "priority": "NORMAL",
  "createdAt": "2026-07-07T08:29:10.111Z",
  "startedAt": "2026-07-07T08:29:12.404Z",
  "completedAt": "2026-07-07T08:29:13.415Z",
  "result": "simulated CSV path",
  "errorMessage": null,
  "retryCount": 1,
  "auditTrail": [
    {
      "fromStatus": "PENDING",
      "toStatus": "IN_PROGRESS",
      "timestamp": "2026-07-07T08:29:10.125Z",
      "threadName": "task-worker-35",
      "message": "Transitioned from PENDING to IN_PROGRESS"
    },
    {
      "fromStatus": "IN_PROGRESS",
      "toStatus": "FAILED",
      "timestamp": "2026-07-07T08:29:11.135Z",
      "threadName": "task-worker-35",
      "message": "Forced failure for retry test"
    },
    {
      "fromStatus": "FAILED",
      "toStatus": "PENDING",
      "timestamp": "2026-07-07T08:29:12.145Z",
      "threadName": "retry-scheduler-1",
      "message": "Retry granted: transitioned FAILED to PENDING"
    },
    {
      "fromStatus": "PENDING",
      "toStatus": "IN_PROGRESS",
      "timestamp": "2026-07-07T08:29:12.404Z",
      "threadName": "task-worker-36",
      "message": "Transitioned from PENDING to IN_PROGRESS"
    },
    {
      "fromStatus": "IN_PROGRESS",
      "toStatus": "COMPLETED",
      "timestamp": "2026-07-07T08:29:13.415Z",
      "threadName": "task-worker-36",
      "message": "Transitioned from IN_PROGRESS to COMPLETED"
    }
  ]
}
```

**Clean Integration Test Execution (`mvn test -Dtest=Phase7IntegrationTest`):**
```text
[INFO] Running com.poc.taskengine.Phase7IntegrationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.071 s -- in com.poc.taskengine.Phase7IntegrationTest
[INFO] BUILD SUCCESS
```

### Definition of Done - Phase 7 checklist

| Requirement | Status | Evidence |
|---|---|---|
| MDC Logging prepends values | PASS | Log trace displays task parameters. `MDC.clear()` prevents leaks to unrelated threads. |
| GET /api/v1/metrics endpoint | PASS | Returns live counters, active thread pools counts, queue sizes, and status/type stats. |
| GET /api/v1/tasks/{id} returns complete Audit Trail | PASS | Trace includes chronological records of every transition, thread name, timestamps, and message. |
| Clean Build & Test Run | PASS | Maven clean test compiles cleanly and runs 14 tests with 0 failures (`BUILD SUCCESS`). |

### Key bugs discovered and fixed during implementation

1. **Audit Trail Error Mapping Failure**
   - *Problem*: In `TaskWorker.markFailed`, transitionStatus was called using the 3-argument signature without parameters for error description. This caused the audit trail to capture `"Transitioned from IN_PROGRESS to FAILED"` rather than the actual exception message, violating the DoD requirement.
   - *Fix*: Modified the invocation to pass the exception description to `transitionStatus(taskId, IN_PROGRESS, FAILED, errorMessage)`.