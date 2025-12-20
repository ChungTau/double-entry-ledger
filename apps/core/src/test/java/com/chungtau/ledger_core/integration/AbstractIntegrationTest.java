package com.chungtau.ledger_core.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Abstract base class providing Testcontainers environment for @SpringBootTest integration tests.
 * All integration tests that use @SpringBootTest should extend this class.
 *
 * Features:
 * - Automatic PostgreSQL container lifecycle management via singleton pattern
 * - Container is shared across all test classes (starts once, stops on JVM shutdown)
 * - @ServiceConnection auto-configures DataSource without manual property registration
 * - Uses "test" profile which configures random ports for gRPC and web server
 *
 * Implementation Notes:
 * - Uses static initialization block to manually start container (ensures singleton)
 * - Container lifecycle is bound to JVM (managed by Ryuk), not individual test classes
 * - @ServiceConnection automatically configures Spring Boot DataSource properties
 * - All subclasses share the same PostgreSQL instance for performance
 *
 * Usage:
 * <pre>
 * {@code
 * @SpringBootTest
 * public class MyIntegrationTest extends AbstractIntegrationTest {
 *     @Test
 *     void testSomething() {
 *         // Test implementation
 *     }
 * }
 * }
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /**
     * Shared PostgreSQL container for all integration tests.
     * Singleton pattern: container starts once in static block and is reused across all test classes.
     * Container lifecycle: starts on class loading, stops on JVM shutdown (managed by Ryuk).
     *
     * Note: We manually start the container in static block instead of using @Container
     * to ensure true singleton behavior. @ServiceConnection automatically configures
     * Spring Boot's DataSource properties without needing @DynamicPropertySource.
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("ledger_test")
            .withUsername("test_user")
            .withPassword("test_password");

    static {
        // Manually start container to bind its lifecycle to JVM instead of individual test classes
        // This ensures all test classes share the same container instance
        postgres.start();
    }

    // No need for @DynamicPropertySource - @ServiceConnection handles it automatically
}
