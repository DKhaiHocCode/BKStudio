package com.bkstudio.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: one real PostgreSQL 16 container shared by every test class
 * in the JVM (singleton, started once; Ryuk removes it on exit). Flyway runs V1 through Spring.
 */
@SpringBootTest
public abstract class AbstractPostgresTest {

    // Static singleton: started once per JVM, shared by all Spring contexts, removed by Ryuk on exit.
    // @ServiceConnection overrides the datasource URL/credentials with the container's.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=300"); // every cached Spring context keeps its own pool

    static {
        POSTGRES.start();
    }
}
