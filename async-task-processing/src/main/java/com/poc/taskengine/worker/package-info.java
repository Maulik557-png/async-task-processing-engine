/**
 * worker — Task execution logic. Workers are handed a Task and run it on
 * the thread pool that matches the task's category. They are stateless;
 * all state mutations go through the service/repository seam.
 * Populated starting Phase 2.
 */
package com.poc.taskengine.worker;
