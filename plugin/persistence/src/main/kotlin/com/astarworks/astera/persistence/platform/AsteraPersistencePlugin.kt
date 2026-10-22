package com.astarworks.astera.persistence.platform

import com.astarworks.astera.core.application.port.outbound.IBlockDurabilityRepository
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
import com.astarworks.astera.core.application.port.outbound.IPlayerAdvancementProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerQuestProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.core.application.port.outbound.IRegionPermissionService
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.application.port.outbound.ISkillProgressionRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.application.port.outbound.IProfileCardService
import com.astarworks.astera.economy.application.port.outbound.ICreatorRevenueRepository
import com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog
import com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository
import com.astarworks.astera.economy.application.port.outbound.IPowerIndexRepository
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingEscrowService
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingHistoryService
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.stats.application.port.outbound.INameplateMilestoneRepository
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
import com.astarworks.astera.persistence.repository.PostgresBlockDurabilityRepository
import com.astarworks.astera.persistence.repository.PostgresPartyRepository
import com.astarworks.astera.persistence.repository.PostgresPermissionService
import com.astarworks.astera.persistence.repository.PostgresPersonalVaultRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerAttributeContainer
import com.astarworks.astera.persistence.repository.PostgresPlayerAdvancementProgressRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerInventorySnapshotRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerQuestProgressRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerSessionRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerSettingsRepository
import com.astarworks.astera.persistence.repository.PostgresEngagementSnapshotRepository
import com.astarworks.astera.persistence.repository.PostgresMatchReplayRepository
import com.astarworks.astera.persistence.repository.PostgresPlayerStatsRepository
import com.astarworks.astera.persistence.repository.PostgresRegionPermissionService
import com.astarworks.astera.persistence.repository.PostgresSkillProgressionRepository
import com.astarworks.astera.persistence.repository.PostgresRewardBridge
import com.astarworks.astera.persistence.repository.PostgresShopStockRepository
import com.astarworks.astera.persistence.repository.PostgresStageRegistry
import com.astarworks.astera.items.application.port.outbound.IItemInstanceRepository
import com.astarworks.astera.match.application.port.outbound.IMatchReplayRepository
import com.astarworks.astera.persistence.repository.PostgresItemInstanceRepository
import com.astarworks.astera.persistence.repository.PostgresNameplateMilestoneRepository
import com.astarworks.astera.persistence.repository.PostgresPowerIndexRepository
import com.astarworks.astera.persistence.repository.PostgresProfileCardService
import com.astarworks.astera.persistence.repository.PostgresBettingEscrowService
import com.astarworks.astera.persistence.repository.PostgresBettingHistoryService
import com.astarworks.astera.persistence.repository.PostgresBettingMatchRepository
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
    private val subscriptions = mutableListOf<com.astarworks.astera.core.application.port.outbound.IDomainEventBus.Subscription>()
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
            // Wave L 5hardening-complete (ADR-0129): structured fan-out for
            // operator-visible DB connection failure. The reporter MUST NOT
            // throw — InMemoryErrorReporter swallows internal errors at WARN
            // (see InMemoryErrorReporter.writeAuditSafe + CriticalSub.invokeSafe).
            services.get<com.astarworks.astera.core.application.port.outbound.error.IErrorReporter>()?.report(
                com.astarworks.astera.core.domain.error.ServerError.Critical(
                    occurredAt = java.time.Instant.now(),
                    summary = "Postgres bootstrap failed (connectAndMigrate threw)",
                    source = com.astarworks.astera.core.domain.error.ErrorSource.Persistence(
                        repositoryName = "AsteraPersistencePlugin/connectAndMigrate",
                    ),
                    causeShortDescription = "${t::class.simpleName}: ${t.message ?: "<no message>"}",
                ),
            )
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
        // F3-4: the lobby and the game server share one player row but each JVM
        // caches it. A player arriving on this backend re-reads the row first, so
        // the wallet / XP / last-match written on the other backend is what shows
        // (and what the next write-through builds on). Subscribed here, before any
        // feature plugin, so it runs ahead of their join listeners.
        services.get<com.astarworks.astera.core.application.port.outbound.IDomainEventBus>()?.let { bus ->
            subscriptions += bus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) {
                attributeContainerStore.reload(it.playerId)
            }
        }
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

        // Wave 5v (ADR-0088): match recording capture persistence. V20
        // migration adds the `match_replays` table; Postgres impl mirrors
        // the in-memory shape with JSONB columns for events / key frames /
        // format. AsteraMatchPlugin's InMemoryMatchReplayRepository (priority
        // 0) is the Postgres-less fallback.
        registrations += services.register<IMatchReplayRepository>(
            PostgresMatchReplayRepository(ctx.database),
            priority = 100,
        )

        // Wave 5refine (ADR-0100): per-player ItemInstance persistence. V30
        // migration adds the `item_instances` table; Postgres impl carries
        // the polymorphic Refinement layers + 原神 sub-stat rolls as JSONB.
        // AsteraItemsPlugin's InMemoryItemInstanceRepository (priority 0) is
        // the Postgres-less fallback.
        registrations += services.register<IItemInstanceRepository>(
            PostgresItemInstanceRepository(ctx.database),
            priority = 100,
        )

        // ── Wave 5flex (ADR-0102) — multi-surface flex layer overlays ─────
        // V34: power_index_snapshots (Networth aggregate).
        // V35: profile_card_snapshots (out-of-game public profile).
        // V36: nameplate_milestones (current display milestone).
        registrations += services.register<IPowerIndexRepository>(
            PostgresPowerIndexRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IProfileCardService>(
            PostgresProfileCardService(ctx.database),
            priority = 100,
        )
        registrations += services.register<INameplateMilestoneRepository>(
            PostgresNameplateMilestoneRepository(ctx.database),
            priority = 100,
        )

        // ── Wave 5bet (ADR-0103) — 1v1 Betting Postgres overlays ─────────
        // V38: betting_matches (per-record lifecycle + audit trail).
        // V39: bet_escrows (per-bet escrow held / paid / refunded record).
        // EconomyPlugin's in-memory defaults (priority 0) are the Postgres-less
        // fallback. The escrow service depends on ICurrencyLedger — by this
        // point our priority-100 PostgresCurrencyLedger has already won, so the
        // injection is end-to-end transactional.
        val pgLedger = services.getOrThrow<ICurrencyLedger>()
        registrations += services.register<IBettingMatchRepository>(
            PostgresBettingMatchRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<IBettingEscrowService>(
            PostgresBettingEscrowService(ctx.database, pgLedger),
            priority = 100,
        )
        registrations += services.register<IBettingHistoryService>(
            PostgresBettingHistoryService(
                db = ctx.database,
                cooldownAfterMatchMs = BettingPolicy.DEFAULT.cooldownAfterMatch.inWholeMilliseconds,
            ),
            priority = 100,
        )

        // Wave 5pve-survival (ADR-0107): Skyblock party-shared island repositories.
        // V40 party_islands + V41 island_plots + V42 gathering_progress.
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository>(
            com.astarworks.astera.persistence.repository.survival.PostgresPartyIslandRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository>(
            com.astarworks.astera.persistence.repository.survival.PostgresIslandPlotRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository>(
            com.astarworks.astera.persistence.repository.survival.PostgresGatheringProgressRepository(ctx.database),
            priority = 100,
        )

        // Wave Q 5port-fill (ADR-0133): audit γ Round 16 finding —
        // PostgresBlockDurabilityRepository (Wave 5x / ADR-0090, V20) class
        // was imported but never registered, leaving block-durability
        // persistence as in-memory-only despite the Postgres impl being
        // shipped + V20 migration applied. KDoc on the repo explicitly
        // promises "Registered by AsteraPersistencePlugin at priority=100";
        // this fills the missing wiring.
        registrations += services.register<IBlockDurabilityRepository>(
            PostgresBlockDurabilityRepository(ctx.database),
            priority = 100,
        )

        // Wave 6c (ADR-0160, V45): write-through block change journal
        // overlays the core-default InMemoryBlockChangeJournalRepository.
        // PaperBlockStateGateway calls save()/delete() per mutation;
        // loadAll() drives plugin-enable warm-up.
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.IBlockChangeJournalRepository>(
            com.astarworks.astera.persistence.repository.PostgresBlockChangeJournalRepository(ctx.database),
            priority = 100,
        )

        // Wave 7b (ADR-0037): Tournament + ranked rating + season leaderboard.
        // V46 tournaments / V47 player_ratings / V48 seasons + leaderboard_rankings.
        registrations += services.register<com.astarworks.astera.match.application.port.outbound.tournament.ITournamentRepository>(
            com.astarworks.astera.persistence.repository.PostgresTournamentRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<com.astarworks.astera.match.application.port.outbound.rating.IPlayerRatingRepository>(
            com.astarworks.astera.persistence.repository.PostgresPlayerRatingRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<com.astarworks.astera.stats.application.port.outbound.ISeasonRepository>(
            com.astarworks.astera.persistence.repository.PostgresSeasonRepository(ctx.database),
            priority = 100,
        )
        registrations += services.register<com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository>(
            com.astarworks.astera.persistence.repository.PostgresLeaderboardRepository(ctx.database),
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
                "IRewardBridge, IShopStockRepository, IMatchReplayRepository, " +
                "IItemInstanceRepository, IPowerIndexRepository, IProfileCardService, " +
                "INameplateMilestoneRepository, IBettingMatchRepository, " +
                "IBettingEscrowService, IBettingHistoryService, " +
                "IPartyIslandRepository, IIslandPlotRepository, IGatheringProgressRepository, " +
                "IBlockDurabilityRepository.",
        )
    }

    override fun onDisable() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        registrations.forEach { it.unregister() }
        registrations.clear()
        persistence?.dataSource?.close()
        persistence = null
        logger.info("Astera-Persistence disabled.")
    }
}
