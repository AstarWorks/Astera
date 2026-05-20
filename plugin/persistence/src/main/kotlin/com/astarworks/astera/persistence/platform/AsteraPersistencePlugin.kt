package com.astarworks.astera.persistence.platform

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.INormaProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.core.application.port.outbound.IRegionPermissionService
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.economy.application.port.outbound.ICreatorRevenueRepository
import com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog
import com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository
import com.astarworks.astera.persistence.PersistenceContext
import com.astarworks.astera.persistence.PostgresConfig
import com.astarworks.astera.persistence.connectAndMigrate
import com.astarworks.astera.persistence.repository.PostgresCreatorRevenueRepository
import com.astarworks.astera.persistence.repository.PostgresCurrencyIssuanceLog
import com.astarworks.astera.persistence.repository.PostgresCurrencyLedger
import com.astarworks.astera.persistence.repository.PostgresFriendshipRepository
import com.astarworks.astera.persistence.repository.PostgresLoadoutRepository
import com.astarworks.astera.persistence.repository.PostgresMarketplaceRepository
import com.astarworks.astera.persistence.repository.PostgresMatchRepository
import com.astarworks.astera.persistence.repository.PostgresNormaProgressRepository
import com.astarworks.astera.persistence.repository.PostgresAuditLogRepository
import com.astarworks.astera.persistence.repository.PostgresPartyRepository
import com.astarworks.astera.persistence.repository.PostgresPermissionService
import com.astarworks.astera.persistence.repository.PostgresPersonalVaultRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerAttributeContainer
import com.astarworks.astera.persistence.repository.PostgresPlayerInventorySnapshotRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerSessionRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerSettingsRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerStatsRepository
import com.astarworks.astera.persistence.repository.PostgresRegionPermissionService
import com.astarworks.astera.persistence.repository.PostgresRewardBridge
import com.astarworks.astera.persistence.repository.PostgresShopStockRepository
import com.astarworks.astera.persistence.repository.PostgresStageRegistry
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
        //
        // Wave 5e: PostgresPlayerAttributeContainer is the canonical storage
        // for player resources (Wallet / PlayerStats / ManaPool facades go
        // through it). PostgresPlayerStatsRepository + PostgresCurrencyLedger
        // are thin facades over it.
        val attributeContainerStore: IPlayerAttributeContainerStore =
            PostgresPlayerAttributeContainer(ctx.database)
        registrations += services.register<IPlayerAttributeContainerStore>(
            attributeContainerStore,
            priority = 100,
        )
        registrations += services.register<IPlayerStatsRepository>(
            PostgresPlayerStatsRepository(attributeContainerStore),
            priority = 100,
        )
        registrations += services.register<IMatchRepository>(
            PostgresMatchRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IStageRegistry>(
            PostgresStageRegistry(ctx.database),
            priority = 100,
        )
        registrations += services.register<ICurrencyLedger>(
            PostgresCurrencyLedger(attributeContainerStore),
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
        // Wave 5e: Session + Social + Vault + Settings + Audit + InventorySnapshot.
        registrations += services.register<IPersonalVaultRepository>(
            PostgresPersonalVaultRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IPlayerSettingsRepository>(
            PostgresPlayerSettingsRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IAuditLogRepository>(
            PostgresAuditLogRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IPlayerInventorySnapshotRepository>(
            PostgresPlayerInventorySnapshotRepository(ctx.database),
            priority = 100,
        )
        // Wave _persist-1: cross-server persistence landmines closed (ADR-0068).
        // SessionState / Friendship / Party / Reward outbox all DB-backed so
        // pod kill / cross-server traversal preserves state.
        registrations += services.register<IPlayerSessionRepository>(
            PostgresPlayerSessionRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IFriendshipRepository>(
            PostgresFriendshipRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IPartyRepository>(
            PostgresPartyRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IRewardBridge>(
            PostgresRewardBridge(ctx.database),
            priority = 100,
        )

        // Wave 5f (ADR-0035): currency issuance log + marketplace + creator revenue.
        registrations += services.register<ICurrencyIssuanceLog>(
            PostgresCurrencyIssuanceLog(ctx.database),
            priority = 100,
        )
        registrations += services.register<IMarketplaceRepository>(
            PostgresMarketplaceRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<ICreatorRevenueRepository>(
            PostgresCreatorRevenueRepository(ctx.database),
            priority = 100,
        )

        // Wave 5g (ADR-0072): shop stock decrement bug fix. V19 migration
        // adds the `shop_stock` table; Postgres impl uses `SELECT ... FOR
        // UPDATE` so concurrent purchases serialize at the row level
        // (in-memory overlay at priority 0 in `AsteraEconomyPlugin` is the
        // Postgres-less fallback).
        registrations += services.register<IShopStockRepository>(
            PostgresShopStockRepository(ctx.database),
            priority = 100,
        )

        // Wave 5n (ADR-0080): per-region permission rules durable across
        // restarts. V25 migration `region_permission`. The Postgres impl
        // depends on the in-memory IStageRegionIndex Core registered at
        // priority 0 — region-to-position resolution is RAM-only because
        // stage geometry is YAML-authored and rebuilt on startup, but the
        // permission RULES need to survive restart so we overlay the
        // service here.
        val stageRegionIndex = services.getOrThrow<IStageRegionIndex>()
        registrations += services.register<IRegionPermissionService>(
            PostgresRegionPermissionService(ctx.database, stageRegionIndex),
            priority = 100,
        )

        logger.info(
            "Astera-Persistence enabled. Postgres overlays registered: " +
                "IPlayerStatsRepository, IMatchRepository, IStageRegistry, " +
                "ICurrencyLedger, IPermissionService, INormaProgressRepository, " +
                "ILoadoutRepository, IPersonalVaultRepository, " +
                "IPlayerSettingsRepository, IAuditLogRepository, " +
                "IPlayerInventorySnapshotRepository, IPlayerAttributeContainerStore, " +
                "ICurrencyIssuanceLog, IMarketplaceRepository, ICreatorRevenueRepository, " +
                "IPlayerSessionRepository, IFriendshipRepository, IPartyRepository, " +
                "IRewardBridge, IShopStockRepository.",
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
