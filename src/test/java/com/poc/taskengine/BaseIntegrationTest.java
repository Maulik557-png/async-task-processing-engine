package com.poc.taskengine;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Base class for all integration tests requiring a real PostgreSQL database.
 * Uses the Singleton Container Pattern to share a single container instance
 * across all inheriting test classes, speeding up execution.
 *
 * <p>If Testcontainers/Docker is not fully accessible in the execution environment,
 * it gracefully falls back to the native PostgreSQL instance running on localhost:5432.
 */
public abstract class BaseIntegrationTest {

    protected static final PostgreSQLContainer<?> postgres;

    static {
        PostgreSQLContainer<?> container = null;
        try {
            container = new PostgreSQLContainer<>("postgres:15-alpine");
            container.withDatabaseName("task_db")
                    .withUsername("postgres")
                    .withPassword("postgres");
            container.start();
            System.out.println("Testcontainers PostgreSQL started successfully.");
        } catch (Exception e) {
            System.err.println("WARNING: Testcontainers failed to start (" + e.getMessage() + "). Gracefully falling back to native host PostgreSQL on port 5432.");
            container = null;
        }
        postgres = container;

        String jdbcUrl = postgres != null ? postgres.getJdbcUrl() : "jdbc:postgresql://localhost:5432/task_db";
        String username = postgres != null ? postgres.getUsername() : "postgres";
        String password = postgres != null ? postgres.getPassword() : System.getenv("SPRING_DATASOURCE_PASSWORD");
        if (password == null) {
            password = "postgres";
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            boolean tasksTableExists = false;
            try (var rs = conn.getMetaData().getTables(null, null, "tasks", null)) {
                if (rs.next()) {
                    tasksTableExists = true;
                }
            }

            if (!tasksTableExists) {
                String v1 = readResource("db/migration/V1__create_tasks_table.sql");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(v1);
                }

                String v2 = readResource("db/migration/V2__create_task_audit_events_table.sql");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(v2);
                }
                System.out.println("Successfully ran SQL migrations against test database schema.");
            }
        } catch (Exception e) {
            System.err.println("Migration run status: " + e.getMessage());
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (postgres != null) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/task_db");
            registry.add("spring.datasource.username", () -> "postgres");
            String envPass = System.getenv("SPRING_DATASOURCE_PASSWORD");
            registry.add("spring.datasource.password", () -> envPass != null ? envPass : "postgres");
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream is = BaseIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new FileNotFoundException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
