package com.chungtau.ledger_core.integration.repository;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;


/**
 * Abstract base class providing Testcontainers PostgreSQL environment for all Repository tests.
 * All Repository tests inherit this class and share the same container instance.
 *
 * Features:
 * - Automatic PostgreSQL container lifecycle management via singleton pattern
 * - Container is truly shared across all test classes (starts once, stops on JVM shutdown)
 * - @ServiceConnection auto-configures DataSource without manual property registration
 * - @DataJpaTest provides lightweight test slice with automatic transaction rollback
 *
 * Implementation Notes:
 * - Uses static initialization block to manually start container (ensures singleton)
 * - Container lifecycle is bound to JVM (managed by Ryuk), not individual test classes
 * - @ServiceConnection automatically configures Spring Boot DataSource properties
 * - No @Testcontainers or @Container annotations (we manage lifecycle manually)
 * - All subclasses share the same PostgreSQL instance for performance
 *
 * Usage:
 * <pre>
 * {@code
 * public class MyRepositoryTest extends AbstractRepositoryTest {
 *     @Autowired
 *     private MyRepository myRepository;
 *
 *     @Test
 *     void testSomething() {
 *         // Test implementation
 *     }
 * }
 * }
 * </pre>
 */
@DataJpaTest
@ActiveProfiles("test")
public abstract class AbstractRepositoryTest {

    /**
     * Shared PostgreSQL container for all Repository tests.
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
