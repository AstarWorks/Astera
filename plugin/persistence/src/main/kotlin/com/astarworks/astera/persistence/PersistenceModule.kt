package com.astarworks.astera.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.Logger

/**
 * Astera-Persistence bootstrap (ADR-0022 / Wave 3 R2).
 *
 * Reads connection settings from environment variables (no Spring, no YAML)
 * — Astera plugins each own their own config domain, and persistence config
 * is purposefully a bootstrap-time concern only.
 *
 * Required env vars:
 *  - `ASTERA_PG_URL` — JDBC URL, e.g. `jdbc:postgresql://pg:5432/astera`
 *  - `ASTERA_PG_USER`
 *  - `ASTERA_PG_PASSWORD`
 *
 * Missing any of these → `fromEnv()` returns `null`, the plugin logs a
 * warning, and feature plugins continue with their in-memory defaults.
 */
public data class PostgresConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    public companion object {
        public fun fromEnv(): PostgresConfig? {
            val url = System.getenv("ASTERA_PG_URL") ?: return null
            val user = System.getenv("ASTERA_PG_USER") ?: return null
            val pw = System.getenv("ASTERA_PG_PASSWORD") ?: return null
            return PostgresConfig(url, user, pw)
        }
    }
}

/**
 * Live persistence handles: the Hikari pool and the Exposed [Database].
 * Hold a reference so [HikariDataSource.close] can be called on plugin
 * disable / test teardown.
 */
public data class PersistenceContext(
    val dataSource: HikariDataSource,
    val database: Database,
)

internal const val MIGRATION_LOCATION: String = "classpath:db/migration"

/**
 * Build a HikariCP pool, run Flyway migrations from `classpath:db/migration`,
 * and bind an Exposed [Database] to the pool.
 *
 * Pool sizing chosen for a Paper server: many short read txns (stat lookups,
 * permission checks) interleaved with rare long-ish writes (match save).
 * 20 / 5 leaves headroom while keeping idle cost low; tune via env or a
 * follow-up ADR if production data shows different patterns.
 */
public fun connectAndMigrate(config: PostgresConfig, logger: Logger): PersistenceContext {
    logger.info("Astera-Persistence: connecting to ${config.url}")

    val hikari = HikariConfig().apply {
        jdbcUrl = config.url
        username = config.user
        password = config.password
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 20
        minimumIdle = 5
        connectionTimeout = 30_000L
        idleTimeout = 600_000L
        poolName = "astera-persistence"
    }
    val ds = HikariDataSource(hikari)

    // Flyway scans via the classloader it is given; the default is the thread context
    // loader, which on Paper is the server loader and cannot see this plugin's jar
    // ("No migrations found"). Pin it to the loader that loaded this plugin's classes.
    val flyway = Flyway.configure(PersistenceContext::class.java.classLoader)
        .dataSource(ds)
        .locations(MIGRATION_LOCATION)
        .baselineOnMigrate(true)
        .load()
    val discovered = flyway.info().all().size
    if (discovered == 0) {
        ds.close()
        error("Astera-Persistence: Flyway resolved 0 migrations from $MIGRATION_LOCATION (classloader scan failed)")
    }
    logger.info("Astera-Persistence: Flyway resolved $discovered migrations")
    flyway.migrate()

    val db = Database.connect(ds)
    logger.info("Astera-Persistence: Flyway migrations applied, Exposed connected")
    return PersistenceContext(ds, db)
}
