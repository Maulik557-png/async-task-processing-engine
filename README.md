# Asynchronous Task Processing Engine

A production-grade, highly concurrent background task processing engine built using **Spring Boot**, **Java Multithreading**, and a transaction-safe **PostgreSQL** persistence layer.

---

## 1. The Problem It Solves

Modern web applications often need to execute heavy, slow, or resource-intensive tasks (such as invoice processing, bulk emails, or report generation) without blocking the user-facing HTTP thread. If HTTP threads wait for these tasks to finish, it leads to connection pool starvation, high response latencies, and server crashes under load. This engine solves that problem by accepting tasks asynchronously over a REST interface, enforcing backpressure limits via a FIFO Semaphore to reject submissions when the server is overloaded, routing accepted tasks into isolated, priority-aware thread pools, and ensuring transaction-safe state transitions, retries, and crashed-task startup recovery backed by a real PostgreSQL database.

---

## 2. System Architecture

Below is the execution flow and component layout of the processing engine:

```text
                         +-----------------------+
                         |     HTTP Client       |
                         +-----------+-----------+
                                     |
                                     v
                        +------------+------------+
                        |  TaskController (REST)  |
                        +------------+------------+
                                     |
                                     v
                     +---------------+---------------+
                     |          TaskService          |
                     |  - Semaphore (FIFO, Max 50)   |
                     |  - Idempotency Race Handler   |
                     +---------------+---------------+
                                     |
                                     +---------------------------------+
                                     | (Enqueue)                       |
                                     v                                 v
                        +------------+------------+       +------------+------------+
                        |   PriorityBlockingQueue |       |      Postgres DB        |
                        |   - Min-Heap Comparator |       |  (Task & Audit Tables)  |
                        +------------+------------+       +------------+------------+
                                     |                                 ^
                                     v (Dequeue)                       | (Read/Lock/Update)
                        +------------+------------+                    |
                        |   CustomThreadPools     |                    |
                        |   - Critical (5 thrs)   |                    |
                        |   - Normal (10 thrs)    |                    |
                        |   - Bulk (5 thrs)       |                    |
                        +------------+------------+                    |
                                     |                                 |
                                     v                                 |
                        +------------+------------+                    |
                        |       TaskWorker        +--------------------+
                        |  - Invokes Handlers     |
                        |  - Watchdog / CB / Retry|
                        +-------------------------+
```

---

## 3. Concurrency Primitives & Persistence Rationale

The engine uses explicit concurrency and database boundary structures rather than relying on automatic/implicit abstractions:

*   **FIFO Semaphore (`Semaphore(50, true)`)**: Enforces strict backpressure limits on incoming tasks. Only 50 in-flight tasks are permitted across the application. New submissions are instantly rejected with HTTP `503 Service Unavailable` once this limit is hit, preventing heap memory exhaustion. Fairness (`true`) ensures tasks are admitted in strict submission order.
*   **Isolated Priority Blocking Queues (`PriorityBlockingQueue`)**: Custom thread pools use min-heap blocking queues to execute urgent tasks (such as `CRITICAL` or `HIGH` priority invoice runs) before lower priority tasks (`NORMAL` or `LOW` bulk emails), avoiding starvation of critical pipelines.
*   **Pessimistic Row Locking (`LockModeType.PESSIMISTIC_WRITE`)**: Handled by Postgres row locks (`SELECT FOR UPDATE`) during status transitions. This ensures that concurrent watchdog evictions, worker retries, or cancellation requests synchronize safely on the database row, preventing race conditions and stale writes.
*   **Targeted JPQL Updates (`@Modifying`)**: Columns (like `status`, `started_at`, `completed_at`, `retry_count`) are modified individually using JPQL instead of calling `save(Entity)`. This avoids lost updates where parallel threads overwrite adjacent fields, reduces database lock times, and maximizes database throughput.
*   **Postgres + Bounded HikariCP Pool vs. H2/Auto-DDL**: 
    *   *Real Database Engine*: Using Postgres ensures compatibility with transaction-isolation levels (Read Committed) and row locking semantics (`FOR UPDATE`) which behave differently in H2 and lead to silent concurrency bugs.
    *   *Manual DDL Validation (`validate`)*: Hibernate is blocked from mutating the database schema (`ddl-auto=validate`). This guarantees production safety by verifying that manually written, source-controlled SQL migration scripts match Java JPA entities exactly.
    *   *Bounded HikariCP Pool (`maximum-pool-size=10`)*: Prevents database connections from growing unbounded, which would otherwise crash the database by exceeding PostgreSQL's connection limits under peak concurrent loads.

---

## 4. How to Run

### Option A: Run via Docker Compose (Recommended)
This starts the full working stack (Application + PostgreSQL database) with proper healthcheck ordering and zero configuration:

1. Copy `.env.example` to `.env` (optional, as defaults are pre-configured):
   ```bash
   cp .env.example .env
   ```
2. Build and start the services:
   ```bash
   docker-compose up --build
   ```
3. Access the application at `http://localhost:8080`.
4. Access the Swagger UI documentation at `http://localhost:8080/swagger-ui.html`.

### Option B: Run Manually (Local Database required)
1. Ensure a PostgreSQL instance is running locally on port `5432` with a database named `task_db`.
2. Set your environment credentials:
   ```powershell
   $env:SPRING_DATASOURCE_USERNAME="postgres"
   $env:SPRING_DATASOURCE_PASSWORD="your_postgres_password"
   ```
3. Build the application and run the test suite:
   ```bash
   mvn clean test
   ```
4. Run the application:
   ```bash
   mvn spring-boot:run
   ```

---

## 5. API Reference & Curl Examples

### 1. Submit a Task
Submit a new task for background execution. If an `idempotencyKey` is provided, resubmissions return the existing task ID immediately.
```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "type": "DATA_EXPORT",
    "priority": "HIGH",
    "payload": "{\"format\": \"csv\"}",
    "submittedBy": "admin-system",
    "maxRetries": 3,
    "idempotencyKey": "export-key-998"
  }'
```
**Response (202 Accepted)**:
```json
{
  "taskId": "e04df2e9-ef12-421b-873b-fde5bc632a4e"
}
```

### 2. Poll Task Details / Audit Logs
Query the status, error trace, result, and lifecycle audit transitions trail.
```bash
curl -X GET http://localhost:8080/api/v1/tasks/e04df2e9-ef12-421b-873b-fde5bc632a4e
```
**Response (200 OK)**:
```json
{
  "taskId": "e04df2e9-ef12-421b-873b-fde5bc632a4e",
  "status": "COMPLETED",
  "type": "DATA_EXPORT",
  "priority": "HIGH",
  "createdAt": "2026-07-10T09:40:00.123Z",
  "startedAt": "2026-07-10T09:40:01.456Z",
  "completedAt": "2026-07-10T09:40:02.987Z",
  "result": "{\"exportedRows\": 1200}",
  "errorMessage": null,
  "retryCount": 0,
  "auditTrail": [
    {
      "id": 1,
      "fromStatus": "PENDING",
      "toStatus": "IN_PROGRESS",
      "timestamp": "2026-07-10T09:40:01.456Z",
      "threadName": "normal-worker-2",
      "message": "Task transitioned to IN_PROGRESS"
    },
    {
      "id": 2,
      "fromStatus": "IN_PROGRESS",
      "toStatus": "COMPLETED",
      "timestamp": "2026-07-10T09:40:02.987Z",
      "threadName": "normal-worker-2",
      "message": "Task completed successfully"
    }
  ]
}
```

### 3. List and Filter Tasks
List all tasks, with optional status filtering (e.g. `?status=IN_PROGRESS`).
```bash
curl -X GET http://localhost:8080/api/v1/tasks?status=COMPLETED
```

### 4. Cancel a Pending Task
Cancel a task before it starts executing. Only permitted if task status is `PENDING`.
```bash
curl -X DELETE http://localhost:8080/api/v1/tasks/e04df2e9-ef12-421b-873b-fde5bc632a4e
```
**Response (204 No Content)**.

### 5. Check Live Metrics
```bash
curl -X GET http://localhost:8080/api/v1/metrics
```
**Response (200 OK)**:
```json
{
  "activeWorkerCount": 1,
  "queueDepth": 0,
  "totalSubmitted": 12,
  "totalCompleted": 11,
  "totalFailed": 1,
  "totalTimedOut": 0,
  "averageExecutionTimeMs": 1542.8,
  "tasksByStatus": {
    "COMPLETED": 11,
    "FAILED": 1
  },
  "tasksByType": {
    "DATA_EXPORT": 12
  }
}
```

---

## 6. Key Design Decisions & Concurrency Tradeoffs

### Idempotency Race Condition Handling
To prevent duplicate processing, the database enforces a unique constraint index on the `idempotency_key` column. When two concurrent API calls submit the same task type with the same key simultaneously:
1. Both calls check the DB. Since the task does not exist, both proceed to insert the row.
2. The database's unique constraint blocks the second insert, throwing a `DataIntegrityViolationException`.
3. The engine catches this exception, **releases the acquired Semaphore permit**, queries the existing row (inserted by the winning thread), and returns its task ID cleanly to the second caller.
4. *Trade-off*: Using database constraints is far safer and more robust than in-memory double-checked locking, as it remains thread-safe and transaction-isolated even when scaling the application horizontally across multiple nodes.

### Crashed Tasks Recovery on Startup
If the application crashes or undergoes a deployment restart mid-execution:
1. At startup, a `@PostConstruct` background worker thread (`task-recovery-thread`) queries the database for any tasks trapped in the `IN_PROGRESS` state.
2. It transitions these tasks back to `PENDING` in a transaction-safe manner (logging the crash in the audit events trail).
3. It re-submits these tasks to their respective executors, respecting Semaphore capacity limits.
4. *Trade-off*: Active profiles and classloader checks (`isRunningTest()`) disable this recovery during integration tests to prevent permit exhaustion and classloader reloading blocks.
