package com.poc.taskengine.repository;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository interface for Task entity database access.
 */
public interface TaskJpaRepository extends JpaRepository<Task, String> {

    List<Task> findByStatus(TaskStatus status);

    Optional<Task> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.taskId = :id")
    Optional<Task> findByIdForUpdate(@Param("id") String id);

    @Modifying
    @Query("UPDATE Task t SET t.status = :status WHERE t.taskId = :id")
    int updateStatusOnly(@Param("id") String id, @Param("status") TaskStatus status);

    @Modifying
    @Query("UPDATE Task t SET t.status = :status, t.startedAt = :startedAt WHERE t.taskId = :id")
    int updateStatusAndStartedAt(@Param("id") String id, @Param("status") TaskStatus status, @Param("startedAt") Instant startedAt);

    @Modifying
    @Query("UPDATE Task t SET t.status = :status, t.completedAt = :completedAt, t.result = :result WHERE t.taskId = :id")
    int updateStatusAndCompletedSuccess(@Param("id") String id, @Param("status") TaskStatus status, @Param("completedAt") Instant completedAt, @Param("result") String result);

    @Modifying
    @Query("UPDATE Task t SET t.status = :status, t.completedAt = :completedAt, t.errorMessage = :errorMessage WHERE t.taskId = :id")
    int updateStatusAndCompletedFailure(@Param("id") String id, @Param("status") TaskStatus status, @Param("completedAt") Instant completedAt, @Param("errorMessage") String errorMessage);

    @Modifying
    @Query("UPDATE Task t SET t.status = :status, t.retryCount = :retryCount, t.startedAt = null, t.completedAt = null, t.errorMessage = :errorMessage, t.result = null WHERE t.taskId = :id")
    int updateStatusForRetry(@Param("id") String id, @Param("status") TaskStatus status, @Param("retryCount") int retryCount, @Param("errorMessage") String errorMessage);
}
