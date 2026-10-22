package com.astarworks.astera.persistence.platform

import com.astarworks.astera.core.application.port.outbound.IBlockDurabilityRepository
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
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
import com.astarworks.astera.core.application.port.outbound.IShopPurchaseRepository
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.application.port.outbound.ISkillProgressionRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
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
import com.astarworks.astera.persistence.repository.PostgresShopPurchaseRepository
import com.astarworks.astera.persistence.repository.PostgresShopStockRepository
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
 * On disable: unregister all overlays and close the Hikari pool.
 */
public class AsteraPersistencePlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()
    private val staged = mutableListOf<Pair<String, (ServiceRegistry) -> ServiceRegistry.Registration>>()
    private val subscriptions = mutableListOf<com.astarworks.astera.core.application.port.outbound.IDomainEventBus.Subscription>()
    private var persistence: PersistenceContext? = null
    private var attributeStore: IPlayerAttributeContainerStore? = null
    private var bootstrapFailure: Throwable? = null

    /** Queues an overlay; nothing is registered until every repository has been built. */
    private inline fun <reified T : Any> stage(impl: T, priority: Int = OVERLAY_PRIORITY) {
        staged += T::class.simpleName.orEmpty() to { services -> services.register<T>(impl, priority) }
    }

    override fun onLoad() {
        val config = PostgresConfig.fromEnv()
        if (config == null) {
            logger.warning(
                "Astera-Persistence: ASTERA_PG_URL / _USER / _PASSWORD not set; " +
                    "no Postgres overlays (every consumer uses the in-memory defaults).",
            )
            return
        }

        val services = getPlugin(AsteraCorePlugin::class.java).services

        val ctx = try {
            connectAndMigrate(config, LoggerFactory.getLogger(javaClass))
        } catch (t: Throwable) {
            failBootstrap(t)
            return
        }
        persistence = ctx

        try {
            stageAll(ctx)
        } catch (t: Throwable) {
            staged.clear()
            ctx.dataSource.close()
            persistence = null
            failBootstrap(t)
            return
        }
        staged.forEach { (_, register) -> registrations += register(services) }
        logger.info(
            "Astera-Persistence: ${staged.size} Postgres overlays registered at priority " +
                "$OVERLAY_PRIORITY during onLoad, before any consumer is constructed: " +
                staged.joinToString(", ") { it.first },
        )
        staged.clear()
    }

    private fun failBootstrap(t: Throwable) {
        bootstrapFailure = t
        logger.severe(
            "Astera-Persistence: CRITICAL bootstrap failed (${t::class.simpleName}: ${t.message}); " +
                "NO Postgres overlays registered, the whole server runs on in-memory defaults.",
        )
    }

    override fun onEnable() {
        // F3-4: the lobby and the game server share one player row but each JVM caches it. A
        // player arriving on this backend re-reads the row first, so the wallet / last-match
        // written on the other backend is what shows. Subscribed before any feature plugin's
        // join listener (loadbefore). Needs Core's bus, so it cannot run in onLoad.
        attributeStore?.let { store ->
            getPlugin(AsteraCorePlugin::class.java).services
                .get<com.astarworks.astera.core.application.port.outbound.IDomainEventBus>()?.let { bus ->
                    subscriptions += bus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) {
                        store.reload(it.playerId)
                    }
                }
        }
        // Core has enabled (its error reporter exists) by now: surface the onLoad failure.
        val t = bootstrapFailure ?: return
        // Wave L 5hardening-complete (ADR-0129): structured fan-out for the
        // operator-visible DB connection failure. The reporter MUST NOT throw.
        getPlugin(AsteraCorePlugin::class.java).services
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

    private fun stageAll(ctx: PersistenceContext) {
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
        stage<IPlayerAttributeContainerStore>(
            attributeContainerStore,
            priority = 100,
        )
        // ADR-0195: onboarding fact + lifetime XP survive restarts (V49 / V50).
        stage<com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository>(
            com.astarworks.astera.persistence.repository.PostgresPlayerProfileRepository(ctx.database),
            priority = 100,
        )
        stage<com.astarworks.astera.stats.application.port.outbound.IPlayerXpRepository>(
            com.astarworks.astera.persistence.repository.PostgresPlayerXpRepository(ctx.database),
            priority = 100,
        )
        stage<com.astarworks.astera.stats.application.port.outbound.ILevelRewardRepository>(
            com.astarworks.astera.persistence.repository.PostgresLevelRewardRepository(ctx.database),
            priority = 100,
        )
        attributeStore = attributeContainerStore
        stage<IPlayerStatsRepository>(
            PostgresPlayerStatsRepository(attributeContainerStore),
            priority = 100,
        )
        stage<IMatchRepository>(
            PostgresMatchRepository(ctx.database),
            priority = 100,
        )
        // IStageRegistry is intentionally NOT overlaid (ADR-0226): stages are content.
        val ledger: ICurrencyLedger = PostgresCurrencyLedger(ctx.database)
        stage<ICurrencyLedger>(
            ledger,
            priority = 100,
        )
        stage<IPermissionService>(
            PostgresPermissionService(ctx.database),
            priority = 100,
        )
        stage<INormaProgressRepository>(
            PostgresNormaProgressRepository(ctx.database),
            priority = 100,
        )
        // Wave 5e: Session + Social + Vault + Settings + Audit + InventorySnapshot.
        stage<IPersonalVaultRepository>(
            PostgresPersonalVaultRepository(ctx.database),
            priority = 100,
        )
        stage<IPlayerSettingsRepository>(
            PostgresPlayerSettingsRepository(ctx.database),
            priority = 100,
        )
        stage<IAuditLogRepository>(
            PostgresAuditLogRepository(ctx.database),
            priority = 100,
        )
        stage<IPlayerInventorySnapshotRepository>(
            PostgresPlayerInventorySnapshotRepository(ctx.database),
            priority = 100,
        )
        // Wave _persist-1: cross-server persistence landmines closed (ADR-0068).
        // SessionState / Friendship / Party / Reward outbox all DB-backed so
        // pod kill / cross-server traversal preserves state.
        stage<IPlayerSessionRepository>(
            PostgresPlayerSessionRepository(ctx.database),
            priority = 100,
        )
        stage<IFriendshipRepository>(
            PostgresFriendshipRepository(ctx.database),
            priority = 100,
        )
        stage<IPartyRepository>(
            PostgresPartyRepository(ctx.database),
            priority = 100,
        )
        stage<IRewardBridge>(
            PostgresRewardBridge(ctx.database),
            priority = 100,
        )

        // Wave 5f (ADR-0035): currency issuance log + marketplace + creator revenue.
        stage<ICurrencyIssuanceLog>(
            PostgresCurrencyIssuanceLog(ctx.database),
            priority = 100,
        )
        stage<IMarketplaceRepository>(
            PostgresMarketplaceRepository(ctx.database),
            priority = 100,
        )
        stage<ICreatorRevenueRepository>(
            PostgresCreatorRevenueRepository(ctx.database),
            priority = 100,
        )

        // Wave 5g (ADR-0072): shop stock decrement bug fix. V19 migration
        // adds the `shop_stock` table; Postgres impl uses `SELECT ... FOR
        // UPDATE` so concurrent purchases serialize at the row level
        // (in-memory overlay at priority 0 in `AsteraEconomyPlugin` is the
        // Postgres-less fallback).
        stage<IShopStockRepository>(
            PostgresShopStockRepository(ctx.database),
            priority = 100,
        )
        // ADR-0211: ownership ledger + purchase history (V64).
        stage<IShopPurchaseRepository>(
            PostgresShopPurchaseRepository(ctx.database),
            priority = 100,
        )

        // Wave 5v (ADR-0088): match recording capture persistence. V20
        // migration adds the `match_replays` table; Postgres impl mirrors
        // the in-memory shape with JSONB columns for events / key frames /
        // format. AsteraMatchPlugin's InMemoryMatchReplayRepository (priority
        // 0) is the Postgres-less fallback.
        stage<IMatchReplayRepository>(
            PostgresMatchReplayRepository(ctx.database),
            priority = 100,
        )

        // Wave 5refine (ADR-0100): per-player ItemInstance persistence. V30
        // migration adds the `item_instances` table; Postgres impl carries
        // the polymorphic Refinement layers + 原神 sub-stat rolls as JSONB.
        // AsteraItemsPlugin's InMemoryItemInstanceRepository (priority 0) is
        // the Postgres-less fallback.
        stage<IItemInstanceRepository>(
            PostgresItemInstanceRepository(ctx.database),
            priority = 100,
        )

        // ── Wave 5flex (ADR-0102) — multi-surface flex layer overlays ─────
        // V34: power_index_snapshots (Networth aggregate).
        // V35: profile_card_snapshots (out-of-game public profile).
        // V36: nameplate_milestones (current display milestone).
        stage<IPowerIndexRepository>(
            PostgresPowerIndexRepository(ctx.database),
            priority = 100,
        )
        stage<IProfileCardService>(
            PostgresProfileCardService(ctx.database),
            priority = 100,
        )
        stage<INameplateMilestoneRepository>(
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
        stage<IBettingMatchRepository>(
            PostgresBettingMatchRepository(ctx.database),
            priority = 100,
        )
        stage<IBettingEscrowService>(
            PostgresBettingEscrowService(ctx.database, ledger),
            priority = 100,
        )
        stage<IBettingHistoryService>(
            PostgresBettingHistoryService(
                db = ctx.database,
                cooldownAfterMatchMs = BettingPolicy.DEFAULT.cooldownAfterMatch.inWholeMilliseconds,
            ),
            priority = 100,
        )

        // Wave 5pve-survival (ADR-0107): Skyblock party-shared island repositories.
        // V40 party_islands + V41 island_plots + V42 gathering_progress.
        stage<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository>(
            com.astarworks.astera.persistence.repository.survival.PostgresPartyIslandRepository(ctx.database),
            priority = 100,
        )
        stage<com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository>(
            com.astarworks.astera.persistence.repository.survival.PostgresIslandPlotRepository(ctx.database),
            priority = 100,
        )
        stage<com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository>(
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
        stage<IBlockDurabilityRepository>(
            PostgresBlockDurabilityRepository(ctx.database),
            priority = 100,
        )

        // Wave 6c (ADR-0160, V45): write-through block change journal
        // overlays the core-default InMemoryBlockChangeJournalRepository.
        // PaperBlockStateGateway calls save()/delete() per mutation;
        // loadAll() drives plugin-enable warm-up.
        stage<com.astarworks.astera.core.application.port.outbound.IBlockChangeJournalRepository>(
            com.astarworks.astera.persistence.repository.PostgresBlockChangeJournalRepository(ctx.database),
            priority = 100,
        )

        // Wave 7b (ADR-0037): Tournament + ranked rating + season leaderboard.
        // V46 tournaments / V47 player_ratings / V48 seasons + leaderboard_rankings.
        stage<com.astarworks.astera.match.application.port.outbound.tournament.ITournamentRepository>(
            com.astarworks.astera.persistence.repository.PostgresTournamentRepository(ctx.database),
            priority = 100,
        )
        stage<com.astarworks.astera.match.application.port.outbound.rating.IPlayerRatingRepository>(
            com.astarworks.astera.persistence.repository.PostgresPlayerRatingRepository(ctx.database),
            priority = 100,
        )
        stage<com.astarworks.astera.stats.application.port.outbound.ISeasonRepository>(
            com.astarworks.astera.persistence.repository.PostgresSeasonRepository(ctx.database),
            priority = 100,
        )
        stage<com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository>(
            com.astarworks.astera.persistence.repository.PostgresLeaderboardRepository(ctx.database),
            priority = 100,
        )

        // ADR-0210: progression state that was in-memory only. Skill progression (V32), quest
        // chains (V27), advancements (V26) and the engagement snapshot (V30) already had
        // Postgres classes that were never staged; achievements (V60) and login streaks (V61) are new.
        stage<ISkillProgressionRepository>(PostgresSkillProgressionRepository(ctx.database), priority = 100)
        stage<com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository>(
            com.astarworks.astera.persistence.repository.PostgresPlayerTutorialStateRepository(ctx.database), priority = 100,
        )
        stage<IPlayerQuestProgressRepository>(PostgresPlayerQuestProgressRepository(ctx.database), priority = 100)
        stage<IPlayerAdvancementProgressRepository>(PostgresPlayerAdvancementProgressRepository(ctx.database), priority = 100)
        stage<com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository>(
            PostgresEngagementSnapshotRepository(ctx.database),
            priority = 100,
        )
        stage<com.astarworks.astera.stats.application.port.outbound.IAchievementRepository>(
            com.astarworks.astera.persistence.repository.PostgresAchievementRepository(ctx.database),
            priority = 100,
        )
        stage<com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginStreakRepository>(
            com.astarworks.astera.persistence.repository.PostgresLoginStreakRepository(ctx.database),
            priority = 100,
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

    private companion object {
        const val OVERLAY_PRIORITY = 100
    }
}
