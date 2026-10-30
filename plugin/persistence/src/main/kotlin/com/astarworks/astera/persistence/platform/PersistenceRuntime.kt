package com.astarworks.astera.persistence.platform

import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.LifecycleFailure
import com.astarworks.astera.persistence.PersistenceContext
import com.astarworks.astera.persistence.PostgresConfig
import com.astarworks.astera.persistence.SwappableDataSource
import com.astarworks.astera.persistence.openPool
import com.astarworks.astera.persistence.runMigrations
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.Logger

/**
 * Persistence's long-lived half (ADR-0311 P3): the Postgres connection. It outlives every wiring generation, which
 * only re-registers overlays on top of it.
 *
 * - [connect] opens the pool, migrates, binds Exposed to a [SwappableDataSource] and builds the carried
 *   repositories. Idempotent: a live connection is kept.
 * - [reconnect] opens and migrates a NEW pool first (a failure leaves the old one serving), swaps it in, then
 *   drains the old pool (waits for checked-out connections, bounded by [drainTimeoutMs]) and closes it.
 * - [overlay] builds this generation's repositories and registers them on a tagged registry view.
 */
internal class PersistenceRuntime(
    private val logger: Logger,
    private val configSource: () -> PostgresConfig? = PostgresConfig::fromEnv,
    private val drainTimeoutMs: Long = DRAIN_TIMEOUT_MS,
) {
    private var swappable: SwappableDataSource? = null
    private var context: PersistenceContext? = null
    private var carried: CarriedRepositories? = null

    /** The cause of the last failed [connect] / [reconnect], for the operator error report. */
    var lastFailure: Throwable? = null
        private set

    val connected: Boolean get() = context != null

    /** The write-through attribute store every generation registers (null until connected). */
    val attributeStore: IPlayerAttributeContainerStore? get() = carried?.attributes

    /**
     * Success(true) connected (now or already), Success(false) nothing configured (env missing: in-memory defaults),
     * Failure the connection could not be established.
     */
    fun connect(): Result<Boolean, LifecycleFailure> {
        if (context != null) return Result.Success(true)
        val config = configSource() ?: return Result.Success(false)
        logger.info("Astera-Persistence: connecting to ${config.url}")
        val pool = openPool(config)
        return try {
            runMigrations(pool, logger)
            val shared = SwappableDataSource(pool)
            val db = Database.connect(shared)
            swappable = shared
            context = PersistenceContext(pool, db)
            carried = CarriedRepositories(db)
            lastFailure = null
            logger.info("Astera-Persistence: Flyway migrations applied, Exposed connected")
            Result.Success(true)
        } catch (t: Throwable) {
            pool.close()
            lastFailure = t
            Result.Failure(LifecycleFailure("${t::class.simpleName}: ${t.message}"))
        }
    }

    /** Replaces the pool behind the shared data source; connects first when there is no connection yet. */
    fun reconnect(): Result<Unit, LifecycleFailure> {
        val shared = swappable
        val current = context
        if (shared == null || current == null) {
            return when (val r = connect()) {
                is Result.Success -> if (r.value) Result.Success(Unit) else Result.Failure(LifecycleFailure("ASTERA_PG_URL / _USER / _PASSWORD are not set"))
                is Result.Failure -> r
            }
        }
        val config = configSource() ?: return Result.Failure(LifecycleFailure("ASTERA_PG_URL / _USER / _PASSWORD are not set"))
        val fresh = openPool(config)
        try {
            runMigrations(fresh, logger)
        } catch (t: Throwable) {
            fresh.close()
            lastFailure = t
            return Result.Failure(LifecycleFailure("${t::class.simpleName}: ${t.message}"))
        }
        val old = shared.swap(fresh)
        context = current.copy(dataSource = fresh)
        lastFailure = null
        drainAndClose(old)
        logger.info("Astera-Persistence: reconnected to ${config.url}")
        return Result.Success(Unit)
    }

    /** Waits for [old]'s checked-out connections to come back, then closes it. In-flight work is never cut short before the timeout. */
    private fun drainAndClose(old: com.zaxxer.hikari.HikariDataSource) {
        val deadline = System.currentTimeMillis() + drainTimeoutMs
        val mx = old.hikariPoolMXBean
        while (mx != null && mx.activeConnections > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(DRAIN_POLL_MS)
        }
        val stuck = mx?.activeConnections ?: 0
        if (stuck > 0) logger.warn("Astera-Persistence: closing the old pool with $stuck connection(s) still checked out after ${drainTimeoutMs}ms")
        old.close()
    }

    /** Builds one generation's overlays and registers them on [services]; empty when not connected. */
    fun overlay(services: ServiceRegistry): List<ServiceRegistry.Registration> {
        val ctx = context ?: return emptyList()
        val overlays = PersistenceOverlays(ctx, checkNotNull(carried))
        overlays.stageAll()
        val registrations = overlays.registerAll(services)
        logger.info(
            "Astera-Persistence: ${overlays.size} Postgres overlays registered at priority " +
                "${PersistenceOverlays.OVERLAY_PRIORITY}: ${overlays.names.joinToString(", ")}",
        )
        return registrations
    }

    /** Process shutdown: close the live pool. */
    fun close() {
        context?.dataSource?.close()
        context = null
        swappable = null
        carried = null
    }

    private companion object {
        const val DRAIN_TIMEOUT_MS = 10_000L
        const val DRAIN_POLL_MS = 50L
    }
}
