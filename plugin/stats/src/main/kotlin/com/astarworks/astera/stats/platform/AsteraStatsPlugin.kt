package com.astarworks.astera.stats.platform

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.stats.application.eventbus.listeners.BroadcastOnLeveledUpListener
import com.astarworks.astera.stats.application.eventbus.listeners.MissionProgressOnKillListener
import com.astarworks.astera.stats.application.eventbus.listeners.RecordMatchPlayedOnMatchEndedListener
import com.astarworks.astera.stats.application.eventbus.listeners.RecordMatchResultOnMatchEndedListener
import com.astarworks.astera.stats.application.eventbus.listeners.RewardOnDamageListener
import com.astarworks.astera.stats.application.eventbus.listeners.RewardOnKillListener
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.application.port.outbound.INormaService
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.stats.application.service.InMemoryLevelService
import com.astarworks.astera.stats.application.service.InMemoryNormaService
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.service.NormaLoaderService
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.application.usecase.mission.CompleteMissionUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordDeathUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordKillUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchPlayedUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchResultUseCase
import com.astarworks.astera.stats.domain.event.LeveledUp
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
        val normaService = InMemoryNormaService()

        // Wave 2 R1.a: load norma definitions from content/norma/ ------------
        val normaContentDir = dataFolder.toPath().resolveSibling("content/norma")
        val normaReport = NormaLoaderService(register = normaService::register).loadFrom(normaContentDir)
        logger.info("Loaded ${normaReport.loaded} norma(s) from $normaContentDir (failed: ${normaReport.failed})")

        // 4. Register Stats ports onto the shared registry ------------------
        // Note: statsRepository is already registered (or resolved from Postgres) above.
        registrations += services.register<ILevelService>(levelService)
        registrations += services.register<INormaService>(normaService)

        // 5. Use cases ------------------------------------------------------
        val recordKill = RecordKillUseCase(statsRepository, eventBus)
        val recordDeath = RecordDeathUseCase(statsRepository, eventBus)
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

        subscriptions += eventBus.subscribe(PlayerKilled::class) { rewardOnKill.handle(it) }
        subscriptions += eventBus.subscribe(DamageDealt::class) { rewardOnDamage.handle(it) }
        subscriptions += eventBus.subscribe(PlayerKilled::class) { missionProgressOnKill.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { recordMatchPlayedListener.handle(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { recordMatchResultListener.handle(it) }
        subscriptions += eventBus.subscribe(LeveledUp::class) { broadcastOnLeveledUpListener.handle(it) }

        // 7. /astera-stats command -----------------------------------------
        commandHandler.register(buildCommandSpec(grantXp, statsRepository, playerGateway, permService))

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
