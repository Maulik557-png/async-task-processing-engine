package com.poc.taskengine.repository;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA/PostgreSQL implementation of TaskRepository.
 * Delegates actual persistence logic to TaskJpaRepository.
 */
@Slf4j
@Profile("!in-memory")
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostgresTaskRepository implements TaskRepository {

    private final TaskJpaRepository delegate;

    @Override
    @Transactional
    public Task save(@NonNull Task task) {
        log.debug("Saving task [{}] to database with status [{}]", task.getTaskId(), task.getStatus());
        return delegate.save(task);
    }

    @Override
    public Optional<Task> findById(@NonNull String taskId) {
        return delegate.findById(taskId);
    }

    @Override
    @Transactional
    public Optional<Task> findByIdForUpdate(@NonNull String taskId) {
        return delegate.findByIdForUpdate(taskId);
    }

    @Override
    public List<Task> findAll() {
        return delegate.findAll();
    }

    @Override
    public List<Task> findByStatus(@NonNull TaskStatus status) {
        return delegate.findByStatus(status);
    }

    @Override
    public Optional<Task> findByIdempotencyKey(String idempotencyKey) {
        return delegate.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    @Transactional
    public void updateStatus(@NonNull String taskId, @NonNull TaskStatus newStatus) {
        log.debug("Database updateStatus for [{}]: {}", taskId, newStatus);
        delegate.updateStatusOnly(taskId, newStatus);
    }

    @Override
    @Transactional
    public void updateStatusAndStartedAt(@NonNull String taskId, @NonNull TaskStatus newStatus, @NonNull Instant startedAt) {
        log.debug("Database updateStatusAndStartedAt for [{}]: status={}, startedAt={}", taskId, newStatus, startedAt);
        delegate.updateStatusAndStartedAt(taskId, newStatus, startedAt);
    }

    @Override
    @Transactional
    public void updateStatusAndCompletedSuccess(@NonNull String taskId, @NonNull TaskStatus newStatus, @NonNull Instant completedAt, String result) {
        log.debug("Database updateStatusAndCompletedSuccess for [{}]: status={}, completedAt={}", taskId, newStatus, completedAt);
        delegate.updateStatusAndCompletedSuccess(taskId, newStatus, completedAt, result);
    }

    @Override
    @Transactional
    public void updateStatusAndCompletedFailure(@NonNull String taskId, @NonNull TaskStatus newStatus, @NonNull Instant completedAt, String errorMessage) {
        log.debug("Database updateStatusAndCompletedFailure for [{}]: status={}, completedAt={}", taskId, newStatus, completedAt);
        delegate.updateStatusAndCompletedFailure(taskId, newStatus, completedAt, errorMessage);
    }

    @Override
    @Transactional
    public void updateStatusForRetry(@NonNull String taskId, @NonNull TaskStatus newStatus, int retryCount, String errorMessage) {
        log.debug("Database updateStatusForRetry for [{}]: status={}, retryCount={}", taskId, newStatus, retryCount);
        delegate.updateStatusForRetry(taskId, newStatus, retryCount, errorMessage);
    }
}
