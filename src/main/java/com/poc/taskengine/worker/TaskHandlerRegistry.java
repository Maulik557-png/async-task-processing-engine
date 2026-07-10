package com.poc.taskengine.worker;

import com.poc.taskengine.enums.TaskType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry containing all registered {@link TaskHandler} implementations.
 *
 * <p>Spring automatically scans and injects all beans implementing {@link TaskHandler}
 * into the constructor. At startup, they are indexed by their supported type.
 */
@Component
public class TaskHandlerRegistry {

    private final Map<TaskType, TaskHandler> handlers = new ConcurrentHashMap<>();

    public TaskHandlerRegistry(List<TaskHandler> handlerList) {
        for (TaskHandler handler : handlerList) {
            handlers.put(handler.getSupportedType(), handler);
        }
    }

    /**
     * Resolves the correct handler for the given task type.
     *
     * @param type the task type to resolve
     * @return the handler instance
     * @throws IllegalArgumentException if no handler is registered for the type
     */
    public TaskHandler getHandler(TaskType type) {
        TaskHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for task type: " + type);
        }
        return handler;
    }

    /**
     * Programmatically registers a handler (primarily used in testing).
     */
    public void registerHandler(TaskType type, TaskHandler handler) {
        handlers.put(type, handler);
    }
}
