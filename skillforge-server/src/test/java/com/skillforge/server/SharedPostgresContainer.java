package com.skillforge.server;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * JVM-wide singleton PostgreSQL container shared across all integration tests.
 *
 * <p>Why: each {@code @Container static} field on {@link AbstractPostgresIT} subclasses
 * would otherwise spin up a new container per test class. With 14+ IT classes on a
 * resource-constrained CI runner (GitHub free-tier 2-core / 7GB), this caused
 * Hikari connection-timeout failures (~30s) — not from container startup, but
 * from the cumulative memory pressure of multiple PG processes plus repeated
 * Flyway migrations.
 *
 * <p>Lifecycle: started once on first {@link #getInstance()} call; stop is a
 * no-op so the container survives across test classes. JVM shutdown will reap
 * the underlying Docker container via Testcontainers' Ryuk reaper.
 *
 * <p>Test isolation is preserved by {@code @DataJpaTest}'s default
 * transaction-rollback behavior — each test sees a clean DB state.
 */
public final class SharedPostgresContainer extends PostgreSQLContainer<SharedPostgresContainer> {

    private static final SharedPostgresContainer INSTANCE = new SharedPostgresContainer();

    private SharedPostgresContainer() {
        super("postgres:16-alpine");
    }

    public static SharedPostgresContainer getInstance() {
        if (!INSTANCE.isRunning()) {
            INSTANCE.start();
        }
        return INSTANCE;
    }

    @Override
    public void stop() {
        // singleton — let JVM shutdown reap via Ryuk
    }
}
