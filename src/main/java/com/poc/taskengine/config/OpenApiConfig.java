package com.poc.taskengine.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class to customize OpenAPI metadata for Swagger UI.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Asynchronous Task Processing Engine API")
                        .version("1.0.0")
                        .description("Production-grade, highly concurrent task processing engine featuring custom executor pools, semaphores, backoff retries, watchdogs, and transaction-safe PostgreSQL state transitions."));
    }
}
