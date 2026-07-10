package com.poc.taskengine.repository;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Optional;

/**
 * Storage contract for Task persistence.
 *
 * Architectural rules that this interface enforces:
 *
 * 1. IMPLEMENTATION INDEPENDENCE — The service layer imports only this interface.
 *    Phase 1 ships with no implementation. Phase 2 provides an in-memory one.
 *    Phase 8 replaces it with a JPA one. Zero service-layer changes required
 *    for either swap; Spring's @Primary / @Qualifier routes the right bean.
 *
 * 2. NO JPA LEAKAGE — Method signatures use only standard Java types
 *    (Optional, List) and our own domain types (Task, TaskStatus).
 *    Nothing here extends JpaRepository, CrudRepository, or any Spring Data type.
 *    If a JPA implementation needs @Query annotations, those live on the
 *    concrete class, not on this interface.
 *
 * 3. updateStatus IS EXPLICIT — Rather than forcing callers to load → mutate →
 *    save, we expose a dedicated updateStatus so the in-memory implementation
 *    can do a compare-and-set and the JPA implementation can issue a targeted
 *    UPDATE query. Both are more efficient and safer for concurrent access than
 *    a full load-modify-save cycle.
 */
public interface TaskRepository {

    /**
     * Persist a new task or overwrite an existing one with the same taskId.
     * Returns the saved Task (may be an enriched copy in JPA implementations).
     *
     * @param task the task to store; must not be null
     * @return the saved task
     */
    Task save(@NonNull Task task);

    /**
     * Look up a task by its unique identifier.
     *
     * @param taskId the UUID string assigned at submission
     * @return an Optional containing the task, or empty if not found
     */
    Optional<Task> findById(@NonNull String taskId);

    /**
     * Look up a task by its unique identifier and acquire a pessimistic write lock.
     */
    Optional<Task> findByIdForUpdate(@NonNull String taskId);

    /**
     * Return all tasks currently held in the store, in no guaranteed order.
     *
     * @return a snapshot list; never null, may be empty
     */
    List<Task> findAll();

    /**
     * Return all tasks in the given status, in no guaranteed order.
     * Used by monitoring endpoints and the retry scanner (Phase 5).
     *
     * @param status the status to filter by; must not be null
     * @return a snapshot list; never null, may be empty
     */
    List<Task> findByStatus(@NonNull TaskStatus status);

    /**
     * Look up a task by its optional idempotency key.
     *
     * @param idempotencyKey the idempotency key to query
     * @return an Optional containing the task, or empty if not found
     */
    Optional<Task> findByIdempotencyKey(String idempotencyKey);

    /**
     * Atomically update the status of a single task.
     *
     * @param taskId    the task to update; must not be null
     * @param newStatus the target status; must not be null
     */
    void updateStatus(@NonNull String taskId, @NonNull TaskStatus newStatus);

    /**
     * Atomically update status and startedAt timestamp.
     */
    void updateStatusAndStartedAt(@NonNull String taskId, @NonNull TaskStatus newStatus, @NonNull java.time.Instant startedAt);

    /**
     * Atomically update status, completedAt, and result for successful completion.
     */
    void updateStatusAndCompletedSuccess(@NonNull String taskId, @NonNull TaskStatus newStatus, @NonNull java.time.Instant completedAt, String result);

    /**
     * Atomically update status, completedAt, and errorMessage for completion failures.
     */
    void updateStatusAndCompletedFailure(@NonNull String taskId, @NonNull TaskStatus newStatus, @NonNull java.time.Instant completedAt, String errorMessage);

    /**
     * Atomically transition a task for retry, incrementing count and resetting transient state.
     */
    void updateStatusForRetry(@NonNull String taskId, @NonNull TaskStatus newStatus, int retryCount, String errorMessage);
}
