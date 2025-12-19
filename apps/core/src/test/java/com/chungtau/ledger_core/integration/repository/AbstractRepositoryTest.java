package com.chungtau.ledger_core.integration.repository;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;


/**
 * Abstract base class providing Testcontainers PostgreSQL environment for all Repository tests.
 * All Repository tests inherit this class and share the same container instance.
 *
 * Features:
 * - Automatic PostgreSQL container lifecycle management via singleton pattern
 * - Container is truly shared across all test classes (starts once, stops on JVM shutdown)
 * - @DynamicPropertySource ensures consistent DataSource configuration
 * - @DataJpaTest provides lightweight test slice with automatic transaction rollback
 *
 * Implementation Notes:
 * - Uses static initialization block to create singleton container
 * - Container is started eagerly and never stopped during test execution
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
     * Singleton pattern: container starts once and is reused across all test classes.
     * Container lifecycle: starts on first access, stops on JVM shutdown.
     */
    private static final PostgreSQLContainer<?> postgres;

    static {
        // Create and start container once for all tests
        postgres = new PostgreSQLContainer<>("postgres:18-alpine")
                .withDatabaseName("ledger_test")
                .withUsername("test_user")
                .withPassword("test_password");
        postgres.start();
    }

    /**
     * Dynamically configures Spring DataSource to use the shared container.
     * This method is called for each test class, but always points to the same container.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
