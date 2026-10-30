package com.astarworks.astera.persistence.platform

import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.wiring.WiringTag
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.platform.wiring.RewireCoordinator
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Astera-Persistence plugin entrypoint (ADR-0022, ADR-0195).
 *
 * Everything happens in [onLoad], which Paper runs for every plugin before any
 * plugin's `onEnable`:
 *  1. Read [PostgresConfig.fromEnv]. Missing → log a warning and register
 *     nothing; every consumer then uses the in-memory defaults.
 *  2. Bootstrap Hikari + Flyway + Exposed via [connectAndMigrate].
 *  3. Build every Postgres repository, and only when ALL were built register
 *     them in Core's [ServiceRegistry] at priority 100. Core wiring (its
 *     `onEnable`) and every feature plugin therefore resolve the final
 *     implementation the first time they ask: no consumer can capture the
 *     priority-0 default (the "overlay split brain").
 *
 * Failure behaviour: a DB outage or migration error logs SEVERE (and is
 * reported through `IErrorReporter` once Core has enabled), registers nothing,
 * and the whole server runs on the in-memory defaults. Never a mix.
 *
 * Rewire (ADR-0311 P3): the pool lives in [PersistenceRuntime]; the overlays are generation 0 of
 * [PersistenceLifecycle], adopted by Core's coordinator in [onEnable]. `astera admin rewire persistence` builds a
 * fresh set of overlays as a new generation (and rebuilds the plugins that consume them); `--reconnect` also swaps
 * the pool behind the shared data source first.
 *
 * On disable: retire the generation (unregisters all overlays) and close the Hikari pool.
 */
public class AsteraPersistencePlugin : JavaPlugin() {

    private val runtime = PersistenceRuntime(LoggerFactory.getLogger(AsteraPersistencePlugin::class.java))
    private val lifecycle = PersistenceLifecycle(runtime)
    private var coordinator: RewireCoordinator? = null
    private var bootstrapFailure: Throwable? = null

    override fun onLoad() {
        val core = getPlugin(AsteraCorePlugin::class.java)
        when (val connected = runtime.connect()) {
            is Result.Failure -> {
                failBootstrap(runtime.lastFailure ?: IllegalStateException(connected.error.message))
                return
            }
            is Result.Success -> if (!connected.value) {
                logger.warning(
                    "Astera-Persistence: ASTERA_PG_URL / _USER / _PASSWORD not set; " +
                        "no Postgres overlays (every consumer uses the in-memory defaults).",
                )
                return
            }
        }
        // Generation 0 of the lifecycle: registered here, before any consumer exists (ADR-0195), and adopted by the
        // rewire coordinator once Core has enabled.
        try {
            runtime.overlay(core.services.scoped(WiringTag(PersistenceLifecycle.OWNER, 0)))
        } catch (t: Throwable) {
            core.services.unregisterTag(WiringTag(PersistenceLifecycle.OWNER, 0))
            runtime.close()
            failBootstrap(t)
        }
    }

    private fun failBootstrap(t: Throwable) {
        bootstrapFailure = t
        logger.severe(
            "Astera-Persistence: CRITICAL bootstrap failed (${t::class.simpleName}: ${t.message}); " +
                "NO Postgres overlays registered, the whole server runs on in-memory defaults.",
        )
    }

    override fun onEnable() {
        val core = getPlugin(AsteraCorePlugin::class.java)
        val rewire = core.rewire
        when (val up = rewire.adopt(lifecycle, PersistenceWired(runtime.attributeStore))) {
            is Result.Success -> coordinator = rewire
            is Result.Failure -> logger.severe("Astera-Persistence: rewire lifecycle failed to start: ${up.error.message}")
        }
        // Core has enabled (its error reporter exists) by now: surface the onLoad failure.
        val t = bootstrapFailure ?: return
        // Wave L 5hardening-complete (ADR-0129): structured fan-out for the
        // operator-visible DB connection failure. The reporter MUST NOT throw.
        core.services
            .get<com.astarworks.astera.core.application.port.outbound.error.IErrorReporter>()?.report(
                com.astarworks.astera.core.domain.error.ServerError.Critical(
                    occurredAt = java.time.Instant.now(),
                    summary = "Postgres bootstrap failed; running on in-memory repositories",
                    source = com.astarworks.astera.core.domain.error.ErrorSource.Persistence(
                        repositoryName = "AsteraPersistencePlugin/onLoad",
                    ),
                    causeShortDescription = "${t::class.simpleName}: ${t.message ?: "<no message>"}",
                ),
            )
    }

    override fun onDisable() {
        coordinator?.down(PersistenceLifecycle.OWNER)
        coordinator = null
        runtime.close()
        logger.info("Astera-Persistence disabled.")
    }
}
