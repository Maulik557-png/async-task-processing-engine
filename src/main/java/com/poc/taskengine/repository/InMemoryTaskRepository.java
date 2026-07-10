package com.poc.taskengine.repository;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of TaskRepository backed by ConcurrentHashMap.
 */
@Slf4j
@Profile("in-memory")
@Repository
public class InMemoryTaskRepository implements TaskRepository {

    private final ConcurrentHashMap<String, Task> store = new ConcurrentHashMap<>(64);

    @Override
    public Task save(@NonNull Task task) {
        String key = task.getIdempotencyKey();
        if (key != null && !key.trim().isEmpty()) {
            for (Task t : store.values()) {
                if (key.equals(t.getIdempotencyKey()) && !t.getTaskId().equals(task.getTaskId())) {
                    throw new DataIntegrityViolationException("Duplicate key violation on: " + key);
                }
            }
        }
        store.put(task.getTaskId(), task);
        log.debug("Saved task [{}] with status [{}]", task.getTaskId(), task.getStatus());
        return task;
    }

    @Override
    public Optional<Task> findById(@NonNull String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public Optional<Task> findByIdForUpdate(@NonNull String taskId) {
        return findById(taskId);
    }

    @Override
    public List<Task> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<Task> findByStatus(@NonNull TaskStatus status) {
        List<Task> result = new ArrayList<>();
        for (Task task : store.values()) {
            if (task.getStatus() == status) {
                result.add(task);
            }
        }
        return result;
    }

    @Override
    public Optional<Task> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return store.values().stream()
                .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
                .findFirst();
    }

    @Override
    public void updateStatus(@NonNull String taskId, @NonNull TaskStatus newStatus) {
        store.compute(taskId, (id, existing) -> {
            if (existing == null) {
                log.warn("updateStatus called on unknown taskId [{}] — ignoring", taskId);
                return null;
            }
            log.debug("Task [{}] status transition: [{}] → [{}]", id, existing.getStatus(), newStatus);
            existing.setStatus(newStatus);
            return existing;
        });
    }

    @Override
    public void updateStatusAndStartedAt(@NonNull String taskId, @NonNull TaskStatus newStatus, @NonNull java.time.Instant startedAt) {
        store.compute(taskId, (id, existing) -> {
            if (existing == null) {
                log.warn("updateStatusAndStartedAt called on unknown taskId [{}] — ignoring", taskId);
                return null;
            }
            log.debug("Task [{}] status transition: [{}] → [{}] (startedAt={})", id, existing.getStatus(), newStatus, startedAt);
            existing.setStatus(newStatus);
            existing.setStartedAt(startedAt);
            return existing;
        });
    }

    @Override
    public void updateStatusAndCompletedSuccess(@NonNull String taskId, @NonNull TaskStatus newStatus, @NonNull java.time.Instant completedAt, String result) {
        store.compute(taskId, (id, existing) -> {
            if (existing == null) {
                log.warn("updateStatusAndCompletedSuccess called on unknown taskId [{}] — ignoring", taskId);
                return null;
            }
            log.debug("Task [{}] status transition: [{}] → [{}] (completedAt={}, result={})", id, existing.getStatus(), newStatus, completedAt, result);
            existing.setStatus(newStatus);
            existing.setCompletedAt(completedAt);
            existing.setResult(result);
            return existing;
        });
    }

    @Override
    public void updateStatusAndCompletedFailure(@NonNull String taskId, @NonNull TaskStatus newStatus, @NonNull java.time.Instant completedAt, String errorMessage) {
        store.compute(taskId, (id, existing) -> {
            if (existing == null) {
                log.warn("updateStatusAndCompletedFailure called on unknown taskId [{}] — ignoring", taskId);
                return null;
            }
            log.debug("Task [{}] status transition: [{}] → [{}] (completedAt={}, errorMessage={})", id, existing.getStatus(), newStatus, completedAt, errorMessage);
            existing.setStatus(newStatus);
            existing.setCompletedAt(completedAt);
            existing.setErrorMessage(errorMessage);
            return existing;
        });
    }

    @Override
    public void updateStatusForRetry(@NonNull String taskId, @NonNull TaskStatus newStatus, int retryCount, String errorMessage) {
        store.compute(taskId, (id, existing) -> {
            if (existing == null) {
                log.warn("updateStatusForRetry called on unknown taskId [{}] — ignoring", taskId);
                return null;
            }
            log.debug("Task [{}] status transition for retry: [{}] → [{}] (retryCount={}, errorMessage={})", id, existing.getStatus(), newStatus, retryCount, errorMessage);
            existing.setStatus(newStatus);
            existing.setRetryCount(retryCount);
            existing.setStartedAt(null);
            existing.setCompletedAt(null);
            existing.setErrorMessage(errorMessage);
            existing.setResult(null);
            return existing;
        });
    }
}
