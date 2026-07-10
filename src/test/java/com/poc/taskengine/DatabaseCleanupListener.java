package com.poc.taskengine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

public class DatabaseCleanupListener extends AbstractTestExecutionListener {

    @Override
    public void beforeTestClass(@NonNull TestContext testContext) throws Exception {
        cleanup(testContext);
    }

    @Override
    public void afterTestClass(@NonNull TestContext testContext) throws Exception {
        cleanup(testContext);
    }

    private void cleanup(TestContext testContext) {
        try {
            JdbcTemplate jdbcTemplate = testContext.getApplicationContext().getBean(JdbcTemplate.class);
            jdbcTemplate.execute("TRUNCATE TABLE task_audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE tasks CASCADE");
        } catch (Exception e) {
            // Ignore if context is not fully initialized, or under in-memory profile
        }
    }
}
