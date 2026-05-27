package com.astarworks.astera.stats.platform

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
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.MatchEnded
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
import com.astarworks.astera.stats.application.eventbus.listeners.RefreshNameplateOnAchievementUnlockedListener
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
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Stats plugin entrypoint (ADR-0022, Stage 3-改).
 *
 * Wires the Stats / Level / Norma domain against Core's shared ports and
 * publishes [IPlayerStatsRepository], [ILevelService], [INormaService] onto the
 * [ServiceRegistry] so other plugins (e.g. Economy) can resolve them.
 *
 * Subscribes to [PlayerKilled] and [DamageDealt] events from the central
 * [IDomainEventBus] (soft-dep on Astera-Combat; the plugin still boots without
 * Combat present, subscriptions simply never fire).
 *
 * Registers `/astera-stats` command tree.
 */
public class AsteraStatsPlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()
    private val subscriptions = mutableListOf<IDomainEventBus.Subscription>()

    @Suppress("UNCHECKED_CAST")
    override fun onEnable() {
        logger.info("Astera-Stats enabling…")

        // 1. Resolve Astera-Core --------------------------------------------
        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Stats.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        // 2. Get shared services -------------------------------------------
        @Suppress("UNUSED_VARIABLE")
        val scheduler = services.getOrThrow<IScheduler>()
        @Suppress("UNUSED_VARIABLE")
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
        val normaContentDir = core.contentDir.resolve("norma")
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
        val skillProgressionContentDir = core.contentDir.resolve("skills-progression")
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

        // 7. /astera-stats command -----------------------------------------
        commandHandler.register(buildCommandSpec(grantXp, statsRepository, playerGateway, permService))

        // Wave E 5admin-ops (ADR-0122) — `/astera-grant-xp` flat admin command.
        commandHandler.register(
            com.astarworks.astera.stats.command.admin.AdminGrantXpCommandSpec(
                grantXp = grantXp,
                playerGateway = playerGateway,
                permissionService = permService,
            ).build(),
        )

        logger.info("Astera-Stats enabled.")
    }

    override fun onDisable() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Stats disabled.")
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
}
