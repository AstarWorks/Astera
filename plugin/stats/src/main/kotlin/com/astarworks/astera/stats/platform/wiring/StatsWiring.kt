package com.astarworks.astera.stats.platform.wiring

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.INormaProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISkillPerkRegistry
import com.astarworks.astera.core.application.port.outbound.ISkillProgressionRepository
import com.astarworks.astera.core.application.port.outbound.ISkillXpCurveRegistry
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.application.profile.RecomputeProfileCardUseCase
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.QuestStageCompleted
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.BlockBrokenBySkill
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.event.SkillLevelUp
import com.astarworks.astera.stats.application.eventbus.listeners.BroadcastOnLeveledUpListener
import com.astarworks.astera.stats.application.eventbus.listeners.MissionProgressOnKillListener
import com.astarworks.astera.stats.application.eventbus.listeners.RecordAssistOnAssistCreditedListener
import com.astarworks.astera.stats.application.eventbus.listeners.RecordMatchPlayedOnMatchEndedListener
import com.astarworks.astera.stats.application.eventbus.listeners.RecordMatchResultOnMatchEndedListener
import com.astarworks.astera.stats.application.eventbus.listeners.RewardOnDamageListener
import com.astarworks.astera.stats.application.eventbus.listeners.RewardOnKillListener
import com.astarworks.astera.stats.application.contents.NormaContents
import com.astarworks.astera.stats.application.contents.SkillProgressionContents
import com.astarworks.astera.stats.application.eventbus.listeners.progression.AwardBossSlayerXpOnMobKilledListener
import com.astarworks.astera.stats.application.eventbus.listeners.progression.AwardCombatXpOnDamageDealtListener
import com.astarworks.astera.stats.application.eventbus.listeners.progression.AwardCombatXpOnMatchEndedListener
import com.astarworks.astera.stats.application.eventbus.listeners.progression.AwardMiningXpOnBlockBrokenListener
import com.astarworks.astera.stats.application.eventbus.listeners.progression.AwardTradingXpOnCurrencyChangedListener
import com.astarworks.astera.stats.application.eventbus.listeners.progression.BroadcastOnSkillLevelUpListener
import com.astarworks.astera.stats.application.eventbus.listeners.progression.GrantPerksOnSkillLevelUpListener
import com.astarworks.astera.stats.application.service.progression.InMemorySkillProgressionRepository
import com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.application.port.outbound.INormaRegistry
import com.astarworks.astera.stats.application.port.outbound.INormaService
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.stats.application.eventbus.listeners.AdvanceLevelOnNormaCompletedListener
import com.astarworks.astera.stats.application.eventbus.listeners.RewardOnMatchEndedListener
import com.astarworks.astera.stats.application.eventbus.listeners.RewardOnMissionCompletedListener
import com.astarworks.astera.stats.application.eventbus.listeners.UnlockAchievementOnDamageMilestoneListener
import com.astarworks.astera.stats.application.eventbus.listeners.UnlockAchievementOnKillMilestoneListener
import com.astarworks.astera.stats.application.eventbus.listeners.UnlockAchievementOnMatchWinListener
import com.astarworks.astera.stats.application.eventbus.listeners.NotifyOnAchievementUnlockedListener
import com.astarworks.astera.stats.application.eventbus.listeners.RefreshNameplateOnAchievementUnlockedListener
import com.astarworks.astera.stats.application.eventbus.listeners.flex.RecomputeProfileCardOnAchievementUnlockedListener
import com.astarworks.astera.stats.application.eventbus.listeners.onboarding.GrantAchievementOnTutorialCompletedListener
import com.astarworks.astera.stats.application.eventbus.listeners.quest.UpdateAchievementProgressOnQuestStageCompletedListener
import com.astarworks.astera.stats.application.port.outbound.IAchievementRepository
import com.astarworks.astera.stats.application.port.outbound.INameplateMilestoneRepository
import com.astarworks.astera.stats.application.service.InMemoryAchievementRepository
import com.astarworks.astera.stats.application.service.nameplate.InMemoryNameplateMilestoneRepository
import com.astarworks.astera.stats.application.service.nameplate.NameplateBadgeRegistry
import com.astarworks.astera.stats.application.usecase.nameplate.RecomputeNameplateBadgeUseCase
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import com.astarworks.astera.stats.application.service.InMemoryLevelService
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.application.service.InMemoryNormaProgressRepository
import com.astarworks.astera.stats.application.service.InMemoryNormaService
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.application.usecase.mission.CompleteMissionUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordAssistUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordDeathUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordKillUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchPlayedUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchResultUseCase
import com.astarworks.astera.stats.domain.event.LeveledUp
import com.astarworks.astera.combat.domain.event.AssistCredited
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.platform.wiring.WiringContext

/** What the host needs after [StatsWiring.install]. */
public class StatsWiringResult(
    public val grantXp: GrantXpUseCase,
    private val shutdownHooks: List<() -> Unit>,
) {
    /** Runs the stop hooks wiring registered; subscriptions / registrations are closed by `WiringContext.close()`. */
    public fun shutdown() {
        shutdownHooks.forEach { runCatching(it) }
    }
}

/**
 * Host-neutral Stats wiring (ADR-0174). Pure construction / registration /
 * subscriptions / command specs — no Bukkit. Subscribes to PlayerKilled / DamageDealt on the shared event bus and registers the `/astera-stats` command tree.
 */
public object StatsWiring {

    @Suppress("UNCHECKED_CAST")
    public fun install(ctx: WiringContext, ports: StatsPlatformPorts): StatsWiringResult {
        val shutdownHooks = mutableListOf<() -> Unit>()
        val services = ctx.services
        val logger = ctx.logger
        val registrations = ctx.registrations
        val subscriptions = ctx.subscriptions

        // 2. Get shared services -------------------------------------------
        @Suppress("UNUSED_VARIABLE")
        val scheduler = services.getOrThrow<IScheduler>()
        val messages = services.getOrThrow<IMessageRenderer>()
        val broadcaster = services.getOrThrow<IBroadcaster>()
        val playerGateway = services.getOrThrow<IPlayerGateway>()
        val commandHandler = services.getOrThrow<ICommandHandler>()
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val permService = services.getOrThrow<IPermissionService>()
        val matchRepository = services.getOrThrow<IMatchRepository>()

        // 3. Build Stats-owned impls ----------------------------------------
        // Wave 3 (R2): if Astera-Persistence has registered a Postgres impl at
        // priority=100 (via loadbefore), we pick it up here. Otherwise fall back
        // to in-memory and register at priority=0 for cross-plugin readers.
        val statsRepository: IPlayerStatsRepository =
            services.get<IPlayerStatsRepository>()
                ?: InMemoryPlayerStatsRepository().also {
                    registrations += services.register<IPlayerStatsRepository>(it, priority = 0)
                }
        val levelService = InMemoryLevelService()

        // Wave 4.5 (ADR-0027): Norma progress is now persisted via repository.
        // Postgres impl overlays at priority 100 when Astera-Persistence is loaded.
        val normaProgressRepository: INormaProgressRepository =
            services.get<INormaProgressRepository>()
                ?: InMemoryNormaProgressRepository().also {
                    registrations += services.register<INormaProgressRepository>(it, priority = 0)
                }

        // Wave _infra-2b (ADR-0065): NormaContents replaces the legacy
        // NormaLoaderService callback pattern. Definitions live in the
        // INormaRegistry port; progress stays in the per-player progress
        // repository. The /astera reload normas command routes here via Core's
        // dispatcher.
        val normaContentDir = ctx.contentDir.resolve("norma")
        val normaContents = NormaContents()
        val normaReport = normaContents.bootstrap(normaContentDir)
        logger.info("Loaded ${normaReport.loaded} norma(s) from $normaContentDir (failed: ${normaReport.failed})")
        val normaService = InMemoryNormaService(normaProgressRepository, normaContents)

        // 4. Register Stats ports onto the shared registry ------------------
        // Note: statsRepository is already registered (or resolved from Postgres) above.
        registrations += services.register<ILevelService>(levelService)
        registrations += services.register<INormaService>(normaService)
        registrations += services.register<INormaRegistry>(normaContents, priority = 0)

        // Wave _infra-2b: register norma contents on the /astera reload dispatcher.
        val reloadRegistry = services.getOrThrow<IReloadableContentsRegistry>()
        reloadRegistry.register(normaContents, normaContentDir)

        // 5. Use cases ------------------------------------------------------
        val recordKill = RecordKillUseCase(statsRepository, eventBus)
        val recordDeath = RecordDeathUseCase(statsRepository, eventBus)
        val recordAssist = RecordAssistUseCase(statsRepository, eventBus)
        val grantXp = GrantXpUseCase(levelService, eventBus)
        val recordMatchPlayed = RecordMatchPlayedUseCase(statsRepository, eventBus)
        val recordMatchResult = RecordMatchResultUseCase(statsRepository, eventBus)
        @Suppress("UNUSED_VARIABLE")
        val completeMission = CompleteMissionUseCase(normaService, eventBus)

        // 6. Event listeners + subscriptions --------------------------------
        val rewardOnKill = RewardOnKillListener(recordKill, grantXp)
        val rewardOnDamage = RewardOnDamageListener(statsRepository)
        val missionProgressOnKill = MissionProgressOnKillListener(normaService, eventBus)
        val recordMatchPlayedListener = RecordMatchPlayedOnMatchEndedListener(matchRepository, recordMatchPlayed)
        val recordMatchResultListener = RecordMatchResultOnMatchEndedListener(matchRepository, recordMatchResult)
        val broadcastOnLeveledUpListener = BroadcastOnLeveledUpListener(broadcaster)
        val recordAssistListener = RecordAssistOnAssistCreditedListener(recordAssist)

        subscriptions += eventBus.subscribe(PlayerKilled::class) { rewardOnKill.handle(it) }
        subscriptions += eventBus.subscribe(DamageDealt::class) { rewardOnDamage.handle(it) }
        subscriptions += eventBus.subscribe(PlayerKilled::class) { missionProgressOnKill.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { recordMatchPlayedListener.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { recordMatchResultListener.handle(it) }
        subscriptions += eventBus.subscribe(LeveledUp::class) { broadcastOnLeveledUpListener.handle(it) }
        subscriptions += eventBus.subscribe(AssistCredited::class) { recordAssistListener.handle(it) }

        // Wave 5wireup §G7 — HUD listener for LeveledUp lives in stats per
        // ADR-0022 sibling isolation (LeveledUp is owned by stats; Core
        // cannot import it). The InMemory HUD gateway records the call only
        // until Wave 6b's Paper renderer lands.
        val hudGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IHudGateway>()
        val hudLeveledUpListener = com.astarworks.astera.stats.application.eventbus.listeners.hud
            .HudUpdateOnLeveledUpListener(hudGateway)
        subscriptions += eventBus.subscribe(LeveledUp::class) { hudLeveledUpListener.handle(it) }

        // ---- Wave 5e Achievement + LevelUpMission + Reward listeners ---------
        val achievementRepo = InMemoryAchievementRepository()
        registrations += services.register<IAchievementRepository>(achievementRepo, priority = 0)

        // ── Wave M 5polish-quickwin (ADR-0130 §B) ────────────────────────
        // PlayerAchievementListGuiBindingResolver projects the achievement
        // catalog into `player.achievement.list.<n>` reactive bindings
        // consumed by AchievementViewerGuiSpec. Appended to Core's mutable
        // composite resolver so the renderer picks it up without re-wiring.
        val compositeBindingResolverForStats = services.get<
            com.astarworks.astera.core.application.gui.binding.CompositeGuiBindingResolver,
            >()
        if (compositeBindingResolverForStats != null) {
            val achievementListResolver = com.astarworks.astera.stats.application.service.gui
                .PlayerAchievementListGuiBindingResolver({ achievementRepo })
            compositeBindingResolverForStats.addDelegate(achievementListResolver)
            logger.info(
                "Wave M 5polish-quickwin (ADR-0130 §B) wired: " +
                    "PlayerAchievementListGuiBindingResolver appended to composite resolver.",
            )
        } else {
            logger.warning(
                "Wave M 5polish-quickwin (ADR-0130 §B) skipped: " +
                    "CompositeGuiBindingResolver not registered in core.",
            )
        }
        val unlockAchievement = UnlockAchievementUseCase(achievementRepo, eventBus)
        val killMilestoneListener = UnlockAchievementOnKillMilestoneListener(unlockAchievement, statsRepository)
        val damageMilestoneListener = UnlockAchievementOnDamageMilestoneListener(unlockAchievement, statsRepository)
        val matchWinMilestoneListener = UnlockAchievementOnMatchWinListener(unlockAchievement, statsRepository)
        val advanceLevelOnNormaListener = AdvanceLevelOnNormaCompletedListener(
            levelService,
            grantXp,
            AdvanceLevelOnNormaCompletedListener.DEFAULT_CRITERION_BY_LEVEL,
        )
        // Reward 2-server flow: enqueue bundles on match-end / mission-complete.
        val rewardBridge = services.getOrThrow<IRewardBridge>()
        val rewardOnMatchEnded = RewardOnMatchEndedListener(matchRepository, rewardBridge)
        val rewardOnMissionCompleted = RewardOnMissionCompletedListener(rewardBridge)

        subscriptions += eventBus.subscribe(PlayerKilled::class) { killMilestoneListener.handle(it) }
        subscriptions += eventBus.subscribe(DamageDealt::class) { damageMilestoneListener.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { matchWinMilestoneListener.handle(it) }
        subscriptions += eventBus.subscribe(MissionCompleted::class) { advanceLevelOnNormaListener.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { rewardOnMatchEnded.handle(it) }
        subscriptions += eventBus.subscribe(MissionCompleted::class) { rewardOnMissionCompleted.handle(it) }

        // ---- Wave 5p Advancement progress fan-out (ADR-0088) ------------
        // Bridges combat / economy / match / items events into core's
        // IAdvancementService. Core owns the catalog + use case; stats hosts
        // the listener because it's the deepest plugin that depends on
        // combat + match + core (per ADR-0022 sibling-isolation matrix).
        val advancementService = services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IAdvancementService>()
        val advancementRegistry = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IAdvancementRegistry>()
        val trackAdvancement = com.astarworks.astera.stats.application.eventbus.listeners.advancement.TrackAdvancementProgressListener(
            service = advancementService,
            registry = advancementRegistry,
            matchRepository = matchRepository,
        )
        subscriptions += eventBus.subscribe(PlayerKilled::class) { trackAdvancement.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { trackAdvancement.handle(it) }
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.core.domain.event.CurrencyChanged::class,
        ) { trackAdvancement.handle(it) }
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.core.domain.event.ItemAcquired::class,
        ) { trackAdvancement.handle(it) }

        // ---- Wave 5multi-skill (ADR-0101): multi-axis skill progression -----
        // Repository: Postgres overlay at priority=100 wins when persistence is loaded.
        val skillProgressionRepo: ISkillProgressionRepository =
            services.get<ISkillProgressionRepository>()
                ?: InMemorySkillProgressionRepository().also {
                    registrations += services.register<ISkillProgressionRepository>(it, priority = 0)
                }
        // Content registry doubles as ISkillXpCurveRegistry + ISkillPerkRegistry.
        val skillProgressionContentDir = ctx.contentDir.resolve("skills-progression")
        val skillProgressionContents = SkillProgressionContents()
        val skillProgressionReport = skillProgressionContents.bootstrap(skillProgressionContentDir)
        logger.info(
            "Loaded ${skillProgressionReport.loaded} skill-progression axis/axes from " +
                "$skillProgressionContentDir (failed: ${skillProgressionReport.failed})",
        )
        registrations += services.register<ISkillXpCurveRegistry>(skillProgressionContents, priority = 0)
        registrations += services.register<ISkillPerkRegistry>(skillProgressionContents, priority = 0)
        reloadRegistry.register(skillProgressionContents, skillProgressionContentDir)

        val awardSkillXp = AwardSkillXpUseCase(skillProgressionRepo, skillProgressionContents, eventBus)
        val combatXpOnDamage = AwardCombatXpOnDamageDealtListener(awardSkillXp)
        val combatXpOnMatchEnded = AwardCombatXpOnMatchEndedListener(matchRepository, awardSkillXp)
        val bossSlayerXpOnKill = AwardBossSlayerXpOnMobKilledListener(awardSkillXp)
        val miningXpOnBlockBroken = AwardMiningXpOnBlockBrokenListener(awardSkillXp)
        val tradingXpOnCurrency = AwardTradingXpOnCurrencyChangedListener(awardSkillXp)
        val broadcastSkillLevelUp = BroadcastOnSkillLevelUpListener(broadcaster)
        val grantPerksOnLevelUp = GrantPerksOnSkillLevelUpListener(skillProgressionContents, eventBus, broadcaster)

        subscriptions += eventBus.subscribe(DamageDealt::class) { combatXpOnDamage.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { combatXpOnMatchEnded.handle(it) }
        subscriptions += eventBus.subscribe(MobKilled::class) { bossSlayerXpOnKill.handle(it) }
        subscriptions += eventBus.subscribe(BlockBroken::class) { miningXpOnBlockBroken.onPickaxeBreak(it) }
        subscriptions += eventBus.subscribe(BlockBrokenBySkill::class) { miningXpOnBlockBroken.onSkillBreak(it) }
        subscriptions += eventBus.subscribe(CurrencyChanged::class) { tradingXpOnCurrency.handle(it) }
        subscriptions += eventBus.subscribe(SkillLevelUp::class) { broadcastSkillLevelUp.handle(it) }
        subscriptions += eventBus.subscribe(SkillLevelUp::class) { grantPerksOnLevelUp.handle(it) }

        // ---- Wave 5flex (ADR-0102): nameplate badge surface ------------
        // In-memory badge cache + recomputation use case. The milestone
        // repo overlay (Postgres V36) wins at priority=100 via
        // AsteraPersistencePlugin; the cache stays in-memory because it
        // is a rebuilt projection on every PowerIndexComputed /
        // AchievementUnlocked event.
        val nameplateCache = NameplateBadgeRegistry()
        registrations += services.register(nameplateCache, priority = 0)
        // Resolve the milestone repo (in-memory fallback if Postgres overlay
        // is absent). Phase 1: registered but not yet consumed inline — the
        // join-listener that re-injects the persisted milestone into the
        // cache lands as part of the Paper-side wiring in a follow-up.
        services.get<INameplateMilestoneRepository>()
            ?: InMemoryNameplateMilestoneRepository().also {
                registrations += services.register<INameplateMilestoneRepository>(it, priority = 0)
            }
        // Phase 1: Stats-only providers are wired. Title equip + PowerIndex
        // hooks land in later flex follow-up waves (Wave 11d for cert tier,
        // post-Wave 6 for title equip command). PowerIndex bridge uses the
        // economy plugin's snapshot repo via ServiceRegistry.
        val nameplateRecompute = RecomputeNameplateBadgeUseCase(
            cache = nameplateCache,
            equippedTitleProvider = { null },
        )
        registrations += services.register(nameplateRecompute, priority = 0)
        val nameplateRefreshOnUnlock = RefreshNameplateOnAchievementUnlockedListener(
            cache = nameplateCache,
            recompute = nameplateRecompute,
        )
        subscriptions += eventBus.subscribe(AchievementUnlocked::class) {
            nameplateRefreshOnUnlock.handle(it)
        }

        // Wave H 5content-depth (ADR-0125) — second AchievementUnlocked
        // subscriber. Distinct concern from RefreshNameplateOnAchievementUnlocked
        // (which rebuilds the nameplate badge projection): this listener
        // surfaces the unlock to the player via chat + a Subtitle HUD stub.
        val notifyOnUnlock = NotifyOnAchievementUnlockedListener(
            playerGateway = playerGateway,
            messages = messages,
            hudGateway = hudGateway,
        )
        subscriptions += eventBus.subscribe(AchievementUnlocked::class) {
            notifyOnUnlock.handle(it)
        }

        // ---- Wave K 5integration-wire (ADR-0128) — 3 cross-feature wires ---
        //
        // 1. Tutorial → Achievement: TutorialCompleted unlocks the
        //    tutorial-graduate achievement so the onboarding loop has a
        //    closing milestone the player can see in their badge wall.
        // 2. Quest → Achievement: QuestStageCompleted unlocks the stage's
        //    `linked_achievement_id` (Wave K added the field to
        //    QuestStage). Stages without a link no-op.
        // 3. AchievementUnlocked → ProfileCard: refresh the long-lived
        //    public profile card cache so the unlock shows up on web /
        //    Discord embed without waiting for a scheduler tick.
        val grantAchievementOnTutorial = GrantAchievementOnTutorialCompletedListener(unlockAchievement)
        subscriptions += eventBus.subscribe(TutorialCompleted::class) {
            grantAchievementOnTutorial.handle(it)
        }

        val questChainRegistry = services.getOrThrow<IQuestChainRegistry>()
        val updateAchievementOnQuestStage = UpdateAchievementProgressOnQuestStageCompletedListener(
            chains = questChainRegistry,
            unlockAchievement = unlockAchievement,
        )
        subscriptions += eventBus.subscribe(QuestStageCompleted::class) {
            updateAchievementOnQuestStage.handle(it)
        }

        val recomputeProfileCard = services.getOrThrow<RecomputeProfileCardUseCase>()
        val recomputeProfileCardOnAchievementUnlocked =
            RecomputeProfileCardOnAchievementUnlockedListener(recomputeProfileCard)
        subscriptions += eventBus.subscribe(AchievementUnlocked::class) {
            recomputeProfileCardOnAchievementUnlocked.handle(it)
        }
        logger.info(
            "Wave K 5integration-wire (ADR-0128) wired: " +
                "GrantAchievementOnTutorialCompleted, " +
                "UpdateAchievementProgressOnQuestStageCompleted, " +
                "RecomputeProfileCardOnAchievementUnlocked subscribed.",
        )

        // ── Wave 7b (ADR-0037): competitive leaderboard + season ────────────
        // Season repo seeds a default active season ("season-1") so the
        // leaderboard works without manual setup; Postgres overlays both at
        // priority 100 (V48 seasons / leaderboard_rankings). The listener
        // materialises rankings from Match's PlayerRatingUpdated events.
        val seasonRepository: com.astarworks.astera.stats.application.port.outbound.ISeasonRepository =
            services.get<com.astarworks.astera.stats.application.port.outbound.ISeasonRepository>()
                ?: com.astarworks.astera.stats.application.service.season.InMemorySeasonRepository().also {
                    registrations += services.register<com.astarworks.astera.stats.application.port.outbound.ISeasonRepository>(it, priority = 0)
                }
        val leaderboardRepository: com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository =
            services.get<com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository>()
                ?: com.astarworks.astera.stats.application.service.leaderboard.InMemoryLeaderboardRepository().also {
                    registrations += services.register<com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository>(it, priority = 0)
                }
        val recordRatingListener = com.astarworks.astera.stats.application.eventbus.listeners
            .RecordRatingOnPlayerRatingUpdatedListener(seasonRepository, leaderboardRepository)
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.core.domain.event.PlayerRatingUpdated::class,
        ) { recordRatingListener.handle(it) }

        val queryLeaderboard = com.astarworks.astera.stats.application.usecase.leaderboard
            .QueryLeaderboardUseCase(leaderboardRepository, seasonRepository)
        registrations += services.register(queryLeaderboard, priority = 0)
        registrations += services.register(
            com.astarworks.astera.stats.application.usecase.season.ResetSeasonUseCase(seasonRepository),
            priority = 0,
        )

        // 7. /astera-stats command -----------------------------------------
        commandHandler.register(buildCommandSpec(grantXp, statsRepository, playerGateway, permService))

        // Wave 7b: /astera-leaderboard [n] — top-N of the current season.
        commandHandler.register(buildLeaderboardCommandSpec(queryLeaderboard, playerGateway))

        // Wave E 5admin-ops (ADR-0122) — `/astera-grant-xp` flat admin command.
        commandHandler.register(
            com.astarworks.astera.stats.command.admin.AdminGrantXpCommandSpec(
                grantXp = grantXp,
                playerGateway = playerGateway,
                permissionService = permService,
            ).build(),
        )

        return StatsWiringResult(grantXp = grantXp, shutdownHooks = shutdownHooks.toList())
    }

    private fun buildCommandSpec(
        grantXp: GrantXpUseCase,
        statsRepository: IPlayerStatsRepository,
        playerGateway: IPlayerGateway,
        permService: IPermissionService,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-stats",
        description = MessageKey("astera.stats.command.root"),
        subcommands = listOf(
            // /astera-stats show <playerName>
            CommandSpec.Literal(
                name = "show",
                description = MessageKey("astera.stats.command.show"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "target",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = lambda@ { ctx ->
                    val targetName = ctx.required<String>("target")
                    val targetId = playerGateway.findByName(targetName)
                        ?: return@lambda CommandResult.Failure(
                            MessageKey("astera.stats.command.show.player-not-found"),
                            mapOf("player" to targetName),
                        )
                    // R1.c gating: showing your own stats is open to everyone;
                    // inspecting another player requires astera.stats.show.other
                    // (console bypasses, matching the framework decorator convention).
                    val senderForPermCheck = ctx.sender
                    if (senderForPermCheck != null && senderForPermCheck != targetId) {
                        val perm = Permission("astera.stats.show.other")
                        if (!permService.check(senderForPermCheck, perm)) {
                            return@lambda CommandResult.Failure(
                                MessageKey("astera.permission.denied"),
                                mapOf("permission" to perm.id),
                            )
                        }
                    }
                    val stats = statsRepository.find(targetId)
                    if (stats == null) {
                        playerGateway.sendMessage(
                            ctx.sender ?: return@lambda CommandResult.Failure(
                                MessageKey("astera.stats.command.console-not-supported"),
                            ),
                            "[$targetName] No stats recorded yet.",
                        )
                    } else {
                        val msg = "[$targetName] K=${stats.kills} D=${stats.deaths} " +
                            "KD=${"%.2f".format(stats.kdRatio)} " +
                            "DMG=${stats.damageDealt} Taken=${stats.damageTaken}"
                        playerGateway.sendMessage(
                            ctx.sender ?: return@lambda CommandResult.Failure(
                                MessageKey("astera.stats.command.console-not-supported"),
                            ),
                            msg,
                        )
                    }
                    CommandResult.Success
                },
            ),
            // /astera-stats xp grant <playerName> <amount>
            CommandSpec.Literal(
                name = "xp",
                description = MessageKey("astera.stats.command.xp"),
                subcommands = listOf(
                    CommandSpec.Literal(
                        name = "grant",
                        description = MessageKey("astera.stats.command.xp.grant"),
                        subcommands = listOf(
                            CommandSpec.Argument(
                                name = "target",
                                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                            ),
                            CommandSpec.Argument(
                                name = "amount",
                                type = ArgumentType.IntegerArg(min = 1),
                            ),
                        ),
                        executor = lambda@ { ctx ->
                            val targetName = ctx.required<String>("target")
                            val amount = ctx.required<Int>("amount").toLong()
                            val targetId = playerGateway.findByName(targetName)
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.stats.command.xp.player-not-found"),
                                    mapOf("player" to targetName),
                                )
                            val result = grantXp.execute(targetId, amount, XpSource.AdminGrant)
                            if (result.isSuccess) CommandResult.Success
                            else CommandResult.Failure(MessageKey("astera.stats.command.xp.invalid-amount"))
                        },
                    ).requirePermission(
                        permissionService = permService,
                        permission = Permission("astera.stats.xp.grant"),
                    ),
                ),
            ),
        ),
    )

    /**
     * `/astera-leaderboard [n]` — print the top-N players of the current
     * competitive season (default 10), highest Elo first (Wave 7b, ADR-0037).
     * Read-only and open to everyone; rendering is a plain message line per
     * entry (name resolution / GUI is Wave 7c polish).
     */
    private fun buildLeaderboardCommandSpec(
        queryLeaderboard: com.astarworks.astera.stats.application.usecase.leaderboard.QueryLeaderboardUseCase,
        playerGateway: IPlayerGateway,
    ): CommandSpec.Literal {
        fun render(sender: com.astarworks.astera.core.domain.player.PlayerId?, limit: Int): CommandResult {
            if (sender == null) return CommandResult.Failure(MessageKey("astera.stats.command.console-not-supported"))
            return queryLeaderboard.execute(limit).fold(
                onSuccess = { entries ->
                    if (entries.isEmpty()) {
                        playerGateway.sendMessage(sender, "[Leaderboard] No ranked games played yet this season.")
                    } else {
                        playerGateway.sendMessage(sender, "[Leaderboard] Top ${entries.size} this season:")
                        entries.forEachIndexed { i, e ->
                            playerGateway.sendMessage(
                                sender,
                                "#${i + 1} ${e.playerId.uuid.toString().take(8)} " +
                                    "rating=${e.rating} ${e.wins}W-${e.losses}L (${"%.0f".format(e.winRate * 100)}%)",
                            )
                        }
                    }
                    CommandResult.Success
                },
                onFailure = { CommandResult.Failure(MessageKey("astera.leaderboard.unavailable")) },
            )
        }
        return CommandSpec.Literal(
            name = "astera-leaderboard",
            description = MessageKey("astera.leaderboard.command.root"),
            executor = { ctx -> render(ctx.sender, DEFAULT_LEADERBOARD_LIMIT) },
            subcommands = listOf(
                CommandSpec.Literal(
                    name = "top",
                    description = MessageKey("astera.leaderboard.command.top"),
                    subcommands = listOf(
                        CommandSpec.Argument(
                            name = "n",
                            type = ArgumentType.IntegerArg(min = 1, max = 100),
                        ),
                    ),
                    executor = { ctx -> render(ctx.sender, ctx.required<Int>("n")) },
                ),
            ),
        )
    }

    private const val DEFAULT_LEADERBOARD_LIMIT = 10
}
