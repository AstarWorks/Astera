package com.astarworks.astera.persistence.platform

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.INormaProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.application.port.outbound.IStageRepository
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.persistence.PersistenceContext
import com.astarworks.astera.persistence.PostgresConfig
import com.astarworks.astera.persistence.connectAndMigrate
import com.astarworks.astera.persistence.repository.PostgresCurrencyLedger
import com.astarworks.astera.persistence.repository.PostgresLoadoutRepository
import com.astarworks.astera.persistence.repository.PostgresMatchRepository
import com.astarworks.astera.persistence.repository.PostgresNormaProgressRepository
import com.astarworks.astera.persistence.repository.PostgresPermissionService
import com.astarworks.astera.persistence.repository.PostgresPlayerStatsRepository
import com.astarworks.astera.persistence.repository.PostgresStageRepository
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Astera-Persistence plugin entrypoint (ADR-0022 / Wave 3 R2).
 *
 * Behavior:
 *  1. Read [PostgresConfig.fromEnv]. If env vars are missing → log a warning,
 *     skip registration. Each feature plugin then continues with its
 *     in-memory default.
 *  2. Otherwise, bootstrap Hikari + Flyway + Exposed via [connectAndMigrate].
 *  3. Look up Astera-Core's [ServiceRegistry] and register seven Postgres impls
 *     at `priority = 100` so they overlay the in-memory defaults registered
 *     at the default priority by each feature plugin.
 *  4. `loadbefore` in `plugin.yml` guarantees this plugin's `onEnable` runs
 *     before any feature plugin's `onEnable`, so when those plugins look up
 *     `services.getOrThrow<X>()` they get the Postgres impl already on top.
 *
 * On disable: unregister all overlays and close the Hikari pool.
 */
public class AsteraPersistencePlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()
    private var persistence: PersistenceContext? = null

    override fun onEnable() {
        logger.info("Astera-Persistence enabling…")

        val config = PostgresConfig.fromEnv()
        if (config == null) {
            logger.warning(
                "Astera-Persistence: ASTERA_PG_URL / _USER / _PASSWORD not set; " +
                    "skipping Postgres registration (feature plugins will use in-memory defaults).",
            )
            return
        }

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Persistence.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        val ctx = try {
            connectAndMigrate(config, LoggerFactory.getLogger(javaClass))
        } catch (t: Throwable) {
            logger.severe("Astera-Persistence: bootstrap failed: ${t.message}")
            server.pluginManager.disablePlugin(this); return
        }
        persistence = ctx

        // Priority 100 overlays the default-priority InMemory* registrations
        // installed by feature plugins. `loadbefore` in plugin.yml ensures we
        // win the lookup race.
        registrations += services.register<IPlayerStatsRepository>(
            PostgresPlayerStatsRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IMatchRepository>(
            PostgresMatchRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IStageRepository>(
            PostgresStageRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<ICurrencyLedger>(
            PostgresCurrencyLedger(ctx.database),
            priority = 100,
        )
        registrations += services.register<IPermissionService>(
            PostgresPermissionService(ctx.database),
            priority = 100,
        )
        registrations += services.register<INormaProgressRepository>(
            PostgresNormaProgressRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<ILoadoutRepository>(
            PostgresLoadoutRepository(ctx.database),
            priority = 100,
        )

        logger.info(
            "Astera-Persistence enabled. Postgres overlays registered: " +
                "IPlayerStatsRepository, IMatchRepository, IStageRepository, " +
                "ICurrencyLedger, IPermissionService, INormaProgressRepository, " +
                "ILoadoutRepository.",
        )
    }

    override fun onDisable() {
        registrations.forEach { it.unregister() }
        registrations.clear()
        persistence?.dataSource?.close()
        persistence = null
        logger.info("Astera-Persistence disabled.")
    }
}
