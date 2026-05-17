package com.astarworks.astera.persistence.harness

import com.astarworks.astera.persistence.PersistenceContext
import com.astarworks.astera.persistence.PostgresConfig
import com.astarworks.astera.persistence.connectAndMigrate
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Shared lifecycle for Postgres integration tests.
 *
 * - Spins up a single [PostgreSQLContainer] per test class (PER_CLASS).
 * - Runs Flyway migrations from `classpath:db/migration` against the
 *   fresh DB via [connectAndMigrate], wiring an Exposed [Database].
 * - Skips (via [Assumptions]) when Docker is not available — so the
 *   build still passes on developer machines / CI runners without a
 *   Docker daemon.
 *
 * Subclass and use [db] to talk to Exposed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PostgresIntegrationTestBase {

    protected lateinit var context: PersistenceContext
    protected val db: Database get() = context.database

    private var container: PostgreSQLContainer<*>? = null

    @BeforeAll
    public fun setupPostgres() {
        Assumptions.assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker not available; skipping Testcontainers IT",
        )
        val pg = PostgreSQLContainer("postgres:17-alpine").apply { start() }
        container = pg
        context = connectAndMigrate(
            PostgresConfig(pg.jdbcUrl, pg.username, pg.password),
            LoggerFactory.getLogger(this::class.java),
        )
    }

    @AfterAll
    public fun teardownPostgres() {
        if (::context.isInitialized) {
            context.dataSource.close()
        }
        container?.stop()
    }
}
