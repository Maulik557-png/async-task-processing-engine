package com.poc.taskengine.worker;

/**
 * Immutable holder for the result of a successful task execution.
 *
 * @param result the result string returned by the task handler
 */
public record TaskResult(String result) {

    public String getResult() {
        return result;
    }
}
