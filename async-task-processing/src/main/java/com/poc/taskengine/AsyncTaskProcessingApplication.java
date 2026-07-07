package com.poc.taskengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// @EnableScheduling activates the @Scheduled annotation processor.
// Without it, @Scheduled methods are silently ignored — no error, no warning.
// Required for TaskTimeoutWatchdog (Phase 5) to fire periodically.
public class AsyncTaskProcessingApplication {

	public static void main(String[] args) {
		SpringApplication.run(AsyncTaskProcessingApplication.class, args);
	}

}
