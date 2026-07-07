package com.poc.taskengine.repository;

import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of TaskRepository backed by ConcurrentHashMap.
 *
 * WHY ConcurrentHashMap over HashMap:
 *   HashMap is not thread-safe. Concurrent reads + writes corrupt its internal
 *   linked-list/tree structure and can cause infinite loops in get() under JDK 7,
 *   or data loss under JDK 8+. We will have many worker threads reading and writing
 *   task state simultaneously.
 *
 * WHY ConcurrentHashMap over Collections.synchronizedMap(new HashMap<>()):
 *   synchronizedMap wraps every method in a single lock over the entire map.
 *   That serialises ALL concurrent readers, even though reading non-overlapping
 *   keys is perfectly safe to do in parallel.
 *   ConcurrentHashMap uses lock striping — it partitions the backing array into
 *   independent bins. Threads writing to different bins never contend with each
 *   other, and readers never block writers (reads are lock-free for stable entries
 *   via volatile reads). Under our workload (10 worker threads concurrently
 *   updating different task IDs), this means near-zero contention vs. a single
 *   global lock that would serialise all 10 threads.
 *
 * WHY NOT a Guava Cache or Caffeine:
 *   Those add TTL/eviction features we don't need yet, and they'd be an uncontrolled
 *   dependency ahead of the phase that asks for them. Phase 8 replaces this class
 *   with a JPA implementation; the service layer is blind to the swap.
 */
@Slf4j
@Repository
public class InMemoryTaskRepository implements TaskRepository {

    // ConcurrentHashMap initial capacity 64 — generous starting size avoids
    // resize rehashing during the burst submission workload in Phase 2's test.
    // Load factor 0.75 (default) gives a good balance between space and rehash frequency.
    private final ConcurrentHashMap<String, Task> store = new ConcurrentHashMap<>(64);

    @Override
    public Task save(Task task) {
        store.put(task.getTaskId(), task);
        log.debug("Saved task [{}] with status [{}]", task.getTaskId(), task.getStatus());
        return task;
    }

    @Override
    public Optional<Task> findById(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public List<Task> findAll() {
        // Return a snapshot — callers must not assume the list stays consistent
        // after this call returns (ConcurrentHashMap guarantees only the moment of iteration).
        return new ArrayList<>(store.values());
    }

    @Override
    public List<Task> findByStatus(TaskStatus status) {
        List<Task> result = new ArrayList<>();
        // ConcurrentHashMap.values() uses a weakly-consistent iterator: it reflects
        // the state of the map at some point during iteration and will not throw
        // ConcurrentModificationException if another thread modifies the map concurrently.
        for (Task task : store.values()) {
            if (task.getStatus() == status) {
                result.add(task);
            }
        }
        return result;
    }

    @Override
    public void updateStatus(String taskId, TaskStatus newStatus) {
        // ConcurrentHashMap.compute() is atomic at the bin level: the lambda runs
        // under an exclusive lock on just the bin holding this key, so two threads
        // racing to update the same taskId will serialize here without locking the
        // entire map. This is the CAS (compare-and-set) behaviour referenced in the
        // TaskRepository interface Javadoc.
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
}
