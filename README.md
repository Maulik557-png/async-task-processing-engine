## Async Task Processing Engine 

A lightweight async task processing engine built using Spring Boot and Java Multithreading.

This project simulates how real-world job runners and Kafka-like consumers handle background task execution using:

* Bounded thread pools
* Queue-based backpressure
* Graceful degradation under load

### Why this project?

Modern systems rely heavily on background processing (emails, payments, notifications, etc.).
This POC explores how such systems can be built from scratch with controlled concurrency and reliability in mind.

### Key Features

* Async task submission API
* Configurable thread pool (bounded parallelism)
* Internal task queue
* Backpressure handling via queue depth
* Graceful shutdown and failure handling

### Inspiration

This design mirrors internal job processing systems and Kafka consumer behavior in a simplified, educational form.
