package com.astarworks.astera.stats.platform.wiring

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.INormaProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.stats.application.config.LevelRewardsYamlConfig
import com.astarworks.astera.stats.application.config.SeasonsYamlConfig
import com.astarworks.astera.stats.application.port.outbound.ILevelRewardRepository
import com.astarworks.astera.stats.application.service.InMemoryLevelRewardRepository
import com.astarworks.astera.stats.application.usecase.level.GrantLevelRewardsUseCase
import com.astarworks.astera.stats.application.usecase.season.CheckSeasonEndUseCase
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISkillPerkRegistry
import com.astarworks.astera.core.application.port.outbound.ISkillProgressionRepository
import com.astarworks.astera.core.application.port.outbound.ISkillXpCurveRegistry
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.i18n.MessageKey
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
import com.astarworks.astera.stats.application.eventbus.listeners.RecordForfeitLossListener
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
import com.astarworks.astera.stats.application.eventbus.listeners.AdvanceLevelOnNormaCompletedListener
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
import com.astarworks.astera.stats.application.port.outbound.IPlayerXpRepository
import com.astarworks.astera.stats.application.service.InMemoryLevelService
import com.astarworks.astera.stats.application.service.InMemoryPlayerXpRepository
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
    private val binders: List<() -> () -> Unit>,
) {
    private var stops: List<() -> Unit> = emptyList()

    /** Starts the side effects (repeating tasks, lobby board) that [StatsWiring.install] deliberately did not start. */
    public fun bind() {
        stops = binders.map { it() }
    }

    /** Stops what [bind] started; registrations / subscriptions are closed by `WiringContext.close()`. */
    public fun unbind() {
        stops.asReversed().forEach { runCatching(it) }
        stops = emptyList()
    }
}

/**
 * Host-neutral Stats wiring (ADR-0174). Pure construction / registration /
 * subscriptions / command specs — no Bukkit. Subscribes to PlayerKilled / DamageDealt on the shared event bus and registers the `/astera stats` command tree.
 */
public object StatsWiring {

    @Suppress("UNCHECKED_CAST")
    public fun install(ctx: WiringContext, ports: StatsPlatformPorts): StatsWiringResult {
        val binders = mutableListOf<() -> () -> Unit>()
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
        val commands = ctx.commandMount()
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val permService = services.getOrThrow<IPermissionService>()
        val matchRepository = services.getOrThrow<IMatchRepository>()

        // 3. Build Stats-owned impls ----------------------------------------
        // Wave 3 (R2): if Astera-Persistence has registered a Postgres impl at
        // priority=100 (via loadbefore), we pick it up here. Otherwise fall back
        // to in-memory and register at priority=0 for cross-plugin readers.
        val statsRepository: IPlayerStatsRepository =
            ctx.stateful<IPlayerStatsRepository> { InMemoryPlayerStatsRepository() }
        val xpRepository: IPlayerXpRepository =
            ctx.stateful<IPlayerXpRepository> { InMemoryPlayerXpRepository() }
        val levelService = InMemoryLevelService(xpRepository)

        // Wave 4.5 (ADR-0027): Norma progress is now persisted via repository.
        // Postgres impl overlays at priority 100 when Astera-Persistence is loaded.
        val normaProgressRepository: INormaProgressRepository =
            ctx.stateful<INormaProgressRepository> { InMemoryNormaProgressRepository() }

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
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.IPlayerLevelView>(
            object : com.astarworks.astera.core.application.port.outbound.IPlayerLevelView {
                override fun levelOf(playerId: com.astarworks.astera.core.domain.player.PlayerId): Int =
                    levelService.currentLevel(playerId).value
                override fun totalXpOf(playerId: com.astarworks.astera.core.domain.player.PlayerId): Long =
                    levelService.totalXp(playerId).value
            },
        )
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

        subscriptions += eventBus.subscribe(PlayerKilled::class) { recordDeath.execute(it.victim) }
        subscriptions += eventBus.subscribe(PlayerKilled::class) { rewardOnKill.handle(it) }
        val cosmeticKillEffect = com.astarworks.astera.stats.application.eventbus.listeners.PlayCosmeticKillEffectListener(
            cosmetics = { services.get<com.astarworks.astera.core.application.service.cosmetic.CosmeticService>() },
            effects = { services.get<com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticEffectPlayer>() },
        )
        subscriptions += eventBus.subscribe(PlayerKilled::class) { cosmeticKillEffect.handle(it) }
        subscriptions += eventBus.subscribe(DamageDealt::class) { rewardOnDamage.handle(it) }
        subscriptions += eventBus.subscribe(PlayerKilled::class) { missionProgressOnKill.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { recordMatchPlayedListener.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { recordMatchResultListener.handle(it) }
        val recordForfeitLossListener = RecordForfeitLossListener(recordMatchPlayed, recordMatchResult)
        subscriptions += eventBus.subscribe(com.astarworks.astera.match.domain.event.PlayerForfeited::class) { recordForfeitLossListener.handle(it) }
        subscriptions += eventBus.subscribe(LeveledUp::class) { broadcastOnLeveledUpListener.handle(it) }
        subscriptions += eventBus.subscribe(AssistCredited::class) { recordAssistListener.handle(it) }

        // Wave 5wireup §G7 — HUD listener for LeveledUp lives in stats per
        // ADR-0022 sibling isolation (LeveledUp is owned by stats; Core
        // cannot import it). The InMemory HUD gateway records the call only
        // until Wave 6b's Paper renderer lands.
        val hudGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IHudGateway>()
        val playerSoundGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.presentation.IPresentation>()
        val hudLeveledUpListener = com.astarworks.astera.stats.application.eventbus.listeners.hud
            .HudUpdateOnLeveledUpListener(hudGateway, playerSoundGateway)
        subscriptions += eventBus.subscribe(LeveledUp::class) { hudLeveledUpListener.handle(it) }

        // Permanent rewards reconcile on XP and join, recovering XP committed before a crash.
        val progressionDir = ctx.contentDir.resolve("progression")
        val yaml = com.astarworks.astera.core.application.config.parser.YamlContentParser()
        val rewardConfig = yaml.parse(
            java.nio.file.Files.readString(progressionDir.resolve("level-rewards.yaml")),
            LevelRewardsYamlConfig.serializer(),
        )
        val levelRewards = ctx.stateful<ILevelRewardRepository> {
            InMemoryLevelRewardRepository {
                services.get<com.astarworks.astera.core.application.port.outbound.ICurrencyLedger>()
            }
        }
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.IPlayerUnlockView>(levelRewards)
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.IPlayerUnlockStore>(levelRewards)
        val grantLevelRewards = GrantLevelRewardsUseCase(
            rewardConfig.levels.mapValues { it.value.toDomain() }, levelRewards, playerGateway, messages, hudGateway,
            onGranted = { id, level, reward ->
                val ledger = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.ICurrencyLedger>()
                val currency = com.astarworks.astera.core.domain.economy.Currency.GOLD
                if (reward.gold > 0) eventBus.publish(CurrencyChanged(
                    id, currency, reward.gold, ledger.balance(id, currency),
                    com.astarworks.astera.core.domain.event.CurrencyChangeReason.LevelReward(level),
                ))
            },
        )
        registrations += services.register(grantLevelRewards)
        fun reconcileRewards(id: com.astarworks.astera.core.domain.player.PlayerId) {
            grantLevelRewards.execute(id, levelService.currentLevel(id).value).onFailure {
                logger.warning("Level reward reconciliation failed for $id: $it")
            }
        }
        subscriptions += eventBus.subscribe(com.astarworks.astera.stats.domain.event.XpGranted::class) {
            reconcileRewards(it.playerId)
        }
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) {
            reconcileRewards(it.playerId)
        }

        // ---- Wave 5e Achievement + LevelUpMission + Reward listeners ---------
        // ADR-0210: unlocks persist (V60 Postgres overlay wins when present); the catalog is
        // content/achievements/ and was never populated before, so no achievement could unlock.
        val achievementRepo: IAchievementRepository =
            ctx.stateful<IAchievementRepository> { InMemoryAchievementRepository() }
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.IPlayerProgressView>(
            object : com.astarworks.astera.core.application.port.outbound.IPlayerProgressView {
                override fun progressOf(playerId: com.astarworks.astera.core.domain.player.PlayerId) =
                    levelService.currentLevel(playerId).value.let { level ->
                        val start = com.astarworks.astera.stats.domain.level.XpCurve.thresholdToReach(level)
                        com.astarworks.astera.core.application.port.outbound.IPlayerProgressView.AccountProgress(
                            level = level,
                            xpIntoLevel = levelService.totalXp(playerId).value - start,
                            xpForLevel = com.astarworks.astera.stats.domain.level.XpCurve.thresholdToReach(level + 1) - start,
                            achievementsUnlocked = achievementRepo.unlocked(playerId).size,
                            achievementsTotal = achievementRepo.list().size,
                        )
                    }
            },
        )
        val achievementContentDir = ctx.contentDir.resolve("achievements")
        val achievementContents = com.astarworks.astera.stats.application.contents.AchievementContents()
        val achievementReport = achievementContents.bootstrap(achievementContentDir)
        logger.info("Loaded ${achievementReport.loaded} achievement(s) from $achievementContentDir (failed: ${achievementReport.failed})")
        achievementRepo.registerAll(achievementContents.all().toList())
        reloadRegistry.register(
            com.astarworks.astera.stats.application.contents.ReloadSyncedContents(achievementContents) {
                achievementRepo.registerAll(achievementContents.all().toList())
            },
            achievementContentDir,
        )

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
            ctx.onClose { compositeBindingResolverForStats.removeDelegate(achievementListResolver) }
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
        // ADR-0210: a completed mission pays its XP and tells the player (its currency is paid by the economy
        // plugin). This replaced an IRewardBridge enqueue that nothing ever consumed.
        val payOutMission = com.astarworks.astera.stats.application.eventbus.listeners.PayOutMissionCompletedListener(
            grantXp = grantXp,
            normas = normaContents,
            messages = messages,
            playerGateway = playerGateway,
            hudGateway = hudGateway,
        )
        val trackNormaProgress = com.astarworks.astera.stats.application.eventbus.listeners.TrackNormaProgressListener(
            normaService = normaService,
            eventBus = eventBus,
            matchRepository = matchRepository,
        )
        val progressionAchievements = com.astarworks.astera.stats.application.eventbus.listeners.UnlockProgressionAchievementsListener(unlockAchievement)

        subscriptions += eventBus.subscribe(PlayerKilled::class) { killMilestoneListener.handle(it) }
        subscriptions += eventBus.subscribe(DamageDealt::class) { damageMilestoneListener.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { matchWinMilestoneListener.handle(it) }
        subscriptions += eventBus.subscribe(MissionCompleted::class) { advanceLevelOnNormaListener.handle(it) }
        subscriptions += eventBus.subscribe(MissionCompleted::class) { payOutMission.handle(it) }
        subscriptions += eventBus.subscribe(MissionCompleted::class) { progressionAchievements.handle(it) }
        subscriptions += eventBus.subscribe(LeveledUp::class) { progressionAchievements.handle(it) }
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.LoginBonusClaimed::class) { progressionAchievements.handle(it) }
        subscriptions += eventBus.subscribe(DamageDealt::class) { trackNormaProgress.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { trackNormaProgress.handle(it) }
        subscriptions += eventBus.subscribe(com.astarworks.astera.stats.domain.event.XpGranted::class) { trackNormaProgress.handle(it) }

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
            ctx.stateful<ISkillProgressionRepository> { InMemorySkillProgressionRepository() }
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
        val nameplateCache = ctx.stateful { NameplateBadgeRegistry() }
        // Resolve the milestone repo (in-memory fallback if Postgres overlay
        // is absent). Phase 1: registered but not yet consumed inline — the
        // join-listener that re-injects the persisted milestone into the
        // cache lands as part of the Paper-side wiring in a follow-up.
        ctx.stateful<INameplateMilestoneRepository> { InMemoryNameplateMilestoneRepository() }
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
            achievements = achievementRepo,
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
            ctx.stateful<com.astarworks.astera.stats.application.port.outbound.ISeasonRepository> { com.astarworks.astera.stats.application.service.season.InMemorySeasonRepository() }
        val leaderboardRepository: com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository =
            ctx.stateful<com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository> { com.astarworks.astera.stats.application.service.leaderboard.InMemoryLeaderboardRepository() }
        val recordRatingListener = com.astarworks.astera.stats.application.eventbus.listeners
            .RecordRatingOnPlayerRatingUpdatedListener(seasonRepository, leaderboardRepository)
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.core.domain.event.PlayerRatingUpdated::class,
        ) { recordRatingListener.handle(it) }

        val queryLeaderboard = com.astarworks.astera.stats.application.usecase.leaderboard
            .QueryLeaderboardUseCase(leaderboardRepository, seasonRepository)
        registrations += services.register(queryLeaderboard, priority = 0)
        val resetSeason = com.astarworks.astera.stats.application.usecase.season.ResetSeasonUseCase(seasonRepository)
        registrations += services.register(resetSeason, priority = 0)
        if (ctx.serverRole.hostsLobby) {
            val seasons = yaml.parse(
                java.nio.file.Files.readString(progressionDir.resolve("seasons.yaml")), SeasonsYamlConfig.serializer(),
            )
            val checkSeason = CheckSeasonEndUseCase(seasons.seasons, resetSeason) { ended, opened ->
                logger.info("Season $ended ended; opened ${opened.label}")
                val sessions = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository>()
                for (session in sessions.all()) {
                    playerGateway.sendMessage(session.playerId, messages.render(
                        session.playerId, MessageKey("astera.progression.season.ended"),
                        mapOf("season" to ended, "next" to opened.label),
                    ))
                }
            }
            registrations += services.register(checkSeason)
            binders += {
                val seasonTask = scheduler.runRepeatingTicks(ExecutionScope.Io, 1L, 1200L) {
                    checkSeason.execute(java.time.Instant.now()).onFailure { logger.warning("Season check failed: $it") }
                }
                seasonTask::cancel
            }
        }

        // ADR-0210: the season ranking floats in the lobby as a text board.
        if (ctx.serverRole.hostsLobby) {
            val boardDir = ctx.contentDir.resolve("lobby-boards")
            val boardConfigs = mutableListOf<com.astarworks.astera.stats.application.config.LobbyBoardYamlConfig>()
            com.astarworks.astera.core.application.service.ContentLoader(
                parsers = com.astarworks.astera.core.application.service.ContentLoader.DEFAULT_PARSERS,
                deserializer = com.astarworks.astera.stats.application.config.LobbyBoardYamlConfig.serializer(),
            ).loadFrom(boardDir) { boardConfigs += it }
            val holograms = services.get<com.astarworks.astera.core.application.port.outbound.IHologramGateway>()
            val dynamicMessages = services.get<com.astarworks.astera.core.application.port.outbound.IDynamicMessages>()
            if (holograms != null && dynamicMessages != null) {
                for (cfg in boardConfigs) {
                    val board = com.astarworks.astera.stats.application.service.leaderboard.LobbyLeaderboardBoard(
                        query = queryLeaderboard,
                        profiles = { services.get<com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository>() },
                        holograms = holograms,
                        dynamic = dynamicMessages,
                        scheduler = scheduler,
                        world = com.astarworks.astera.core.domain.world.WorldId(cfg.world),
                        position = com.astarworks.astera.core.domain.geometry.Vec3(cfg.x, cfg.y, cfg.z),
                        rows = cfg.rows,
                        refreshTicks = cfg.refreshSeconds * 20L,
                    )
                    binders += {
                        board.start()
                        board::stop
                    }
                }
            }
        }

        // F3-4: back in the lobby, recap the match the player just finished (once).
        if (ctx.serverRole.hostsLobby) {
            val recapOnJoin = com.astarworks.astera.stats.application.eventbus.listeners.ShowLastMatchRecapOnJoinListener(
                lastMatches = { services.get<com.astarworks.astera.core.application.port.outbound.ILastMatchStore>() },
                messages = messages,
                playerGateway = playerGateway,
                scheduler = scheduler,
            )
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) {
                recapOnJoin.handle(it)
            }
        }

        // ---- ADR-0210: quests that actually run, and the daily login bonus ----------
        // Quest chains are open to everyone (started on join) and advance on real events; before this
        // nothing ever called IQuestService.progressCurrent. Stage clears are announced to the player.
        val questService = services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IQuestService>()
        val startQuestsOnJoin = com.astarworks.astera.stats.application.eventbus.listeners.quest
            .StartQuestsOnJoinListener(questService, questChainRegistry)
        val trackQuestProgress = com.astarworks.astera.stats.application.eventbus.listeners.quest
            .TrackQuestProgressListener(questService, questChainRegistry, matchRepository)
        val notifyQuestStage = com.astarworks.astera.stats.application.eventbus.listeners.quest
            .NotifyOnQuestStageCompletedListener(questChainRegistry, messages, playerGateway, hudGateway)
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) { startQuestsOnJoin.handle(it) }
        subscriptions += eventBus.subscribe(PlayerKilled::class) { trackQuestProgress.handle(it) }
        subscriptions += eventBus.subscribe(MobKilled::class) { trackQuestProgress.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { trackQuestProgress.handle(it) }
        subscriptions += eventBus.subscribe(CurrencyChanged::class) { trackQuestProgress.handle(it) }
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.ItemAcquired::class) { trackQuestProgress.handle(it) }
        subscriptions += eventBus.subscribe(QuestStageCompleted::class) { notifyQuestStage.handle(it) }

        val loginBonusService = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusService>()
        val claimLoginBonus = com.astarworks.astera.stats.application.eventbus.listeners.loginbonus.ClaimLoginBonusOnJoinListener(
            loginBonus = loginBonusService,
            messages = messages,
            playerGateway = playerGateway,
            hudGateway = hudGateway,
            scheduler = scheduler,
        )
        if (ctx.serverRole.hostsLobby) {
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) {
                claimLoginBonus.handle(it)
            }
        }
        com.astarworks.astera.stats.command.ProgressionCommands(
            loginBonus = loginBonusService,
            claimDaily = claimLoginBonus::claim,
            normas = normaService,
            quests = questService,
            questChains = questChainRegistry,
            achievements = achievementRepo,
            messages = messages,
        ).build().forEach { commands.register(it) }

        // UI-04: the Daily / Quests screen behind the main-menu tile (`progress.open`); the commands above stay as shortcuts.
        val progressMenu = com.astarworks.astera.stats.application.progress.ProgressMenu(
            loginBonus = loginBonusService,
            claimDaily = claimLoginBonus::claim,
            normas = normaService,
            quests = questService,
            questChains = questChainRegistry,
            adapter = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IGuiAdapter>(),
            server = services.getOrThrow<com.astarworks.astera.core.adapter.api.server.IMcServer>(),
            messages = messages,
        )
        registrations += services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>().register(
            com.astarworks.astera.stats.application.progress.ProgressMenu.INTENT_PREFIX,
        ) { intent, player -> progressMenu.handle(intent, player) }

        // Main-menu "last match result" tile (`stats.last`).
        val lastMatchHandler = com.astarworks.astera.stats.application.progress.LastMatchIntentHandler(
            lastMatches = { services.get<com.astarworks.astera.core.application.port.outbound.ILastMatchStore>() },
            guiAdapter = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IGuiAdapter>(),
            messages = messages,
            playerGateway = playerGateway,
        )
        registrations += services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>().register(
            com.astarworks.astera.stats.application.progress.LastMatchIntentHandler.INTENT,
        ) { intent, player -> lastMatchHandler.handle(intent, player) }

        // 7. `/astera stats show|leaderboard|xp grant` (ADR-0184).
        com.astarworks.astera.stats.command.StatsCommands(
            grantXp = grantXp,
            statsRepository = statsRepository,
            queryLeaderboard = queryLeaderboard,
            players = playerGateway,
            permissions = { services.getOrThrow<IPermissionService>() },
            lastMatches = { services.get<com.astarworks.astera.core.application.port.outbound.ILastMatchStore>() },
            names = { id -> services.get<com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository>()?.find(id)?.displayName },
        ).build().forEach { commands.register("stats", it) }

        return StatsWiringResult(grantXp = grantXp, binders = binders.toList())
    }
}
