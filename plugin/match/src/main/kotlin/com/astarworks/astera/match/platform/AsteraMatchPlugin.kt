package com.astarworks.astera.match.platform

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
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRepository
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.InMemoryStageRepository
import com.astarworks.astera.match.application.service.StageLoaderService
import com.astarworks.astera.match.application.usecase.match.EndMatchError
import com.astarworks.astera.match.application.usecase.match.EndMatchUseCase
import com.astarworks.astera.match.application.usecase.match.EntryGameError
import com.astarworks.astera.match.application.usecase.match.EntryGameUseCase
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseError
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Match plugin entrypoint (ADR-0022 Phase 4 / Stage 3-改).
 *
 * Resolves shared services from Astera-Core, constructs the four lifecycle use
 * cases, exposes them via [IMatchLifecycle] on the [ServiceRegistry], and
 * registers the `/astera-match` command tree.
 *
 * The Persistence plugin (Stage 4-改) registers Postgres-backed impls at
 * higher priority and `services.getOrThrow<IMatchRepository>()` then
 * automatically returns the Postgres version without any wiring change here.
 */
public class AsteraMatchPlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()

    override fun onEnable() {
        logger.info("Astera-Match enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Match.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        // 1. Build match-owned repositories -------------------------------------
        // Wave 3 (R2): pick up Postgres impls (priority=100) if Astera-Persistence
        // registered them via loadbefore; otherwise create in-memory at priority=0.
        val matches: IMatchRepository =
            services.get<IMatchRepository>()
                ?: InMemoryMatchRepository().also {
                    registrations += services.register<IMatchRepository>(it, priority = 0)
                }
        val stages: IStageRepository =
            services.get<IStageRepository>()
                ?: InMemoryStageRepository().also {
                    registrations += services.register<IStageRepository>(it, priority = 0)
                }

        // Wave 2 R1.a: load stage definitions from content/stages/ -------------
        val stageLoader = StageLoaderService(stages)
        val stagesContentDir = dataFolder.toPath().resolveSibling("content/stages")
        val stageReport = stageLoader.loadFrom(stagesContentDir)
        logger.info("Loaded ${stageReport.loaded} stage(s) from $stagesContentDir (failed: ${stageReport.failed})")

        // 2. Resolve shared core services ---------------------------------------
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val scheduler = services.getOrThrow<IScheduler>()
        val playerGateway = services.getOrThrow<IPlayerGateway>()
        val commandHandler = services.getOrThrow<ICommandHandler>()
        val permService = services.getOrThrow<IPermissionService>()

        @Suppress("UnusedVariable")
        val broadcaster = services.getOrThrow<IBroadcaster>()

        @Suppress("UnusedVariable")
        val msgRenderer = services.getOrThrow<IMessageRenderer>()

        // 3. Construct use cases -----------------------------------------------
        val startMatch = StartMatchUseCase(stages, matches, eventBus, scheduler)
        val endMatch = EndMatchUseCase(matches, eventBus, scheduler)
        val entryGame = EntryGameUseCase(matches, eventBus)
        val switchPhase = SwitchPhaseUseCase(matches, eventBus, scheduler)

        // 4. Expose IMatchLifecycle via ServiceRegistry -------------------------
        val facade = object : IMatchLifecycle {
            override fun start(stageId: StageId) = startMatch.execute(stageId)
            override fun end(matchId: MatchId, reason: EndReason) = endMatch.execute(matchId, reason)
            override fun entry(matchId: MatchId, playerId: PlayerId, teamId: TeamId?) =
                entryGame.execute(matchId, playerId, teamId)
            override fun switchPhase(matchId: MatchId, trigger: MatchStateMachine.TransitionTrigger) =
                switchPhase.execute(matchId, trigger)
        }
        registrations += services.register<IMatchLifecycle>(facade)

        // 5. Register /astera-match command tree --------------------------------
        commandHandler.register(buildMatchCommandSpec(facade, matches, playerGateway, permService))

        logger.info("Astera-Match enabled. Services: IMatchRepository, IStageRepository, IMatchLifecycle")
    }

    override fun onDisable() {
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Match disabled.")
    }

    private fun buildMatchCommandSpec(
        lifecycle: IMatchLifecycle,
        matches: IMatchRepository,
        playerGateway: IPlayerGateway,
        permService: IPermissionService,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-match",
        description = MessageKey("astera.match.command.root"),
        subcommands = listOf(
            // astera-match start <stageId>
            CommandSpec.Literal(
                name = "start",
                description = MessageKey("astera.match.command.start"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "stage-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val stageIdStr = ctx.required<String>("stage-id")
                    val stageId = runCatching { StageId(stageIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.start.invalid-stage"),
                            mapOf("stage" to stageIdStr),
                        )
                    when (val result = lifecycle.start(stageId)) {
                        is Result.Success -> {
                            val senderId = ctx.sender
                            if (senderId != null) {
                                playerGateway.sendMessage(senderId, "Match started: ${result.value.value}")
                            }
                            CommandResult.Success
                        }
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.start"),
            ),
            // astera-match end <matchId>
            CommandSpec.Literal(
                name = "end",
                description = MessageKey("astera.match.command.end"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.end.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    when (val result = lifecycle.end(matchId, EndReason.AdminEnded)) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.end"),
            ),
            // astera-match entry <matchId> <playerName>
            CommandSpec.Literal(
                name = "entry",
                description = MessageKey("astera.match.command.entry"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                    CommandSpec.Argument(
                        name = "player",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val playerName = ctx.required<String>("player")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.entry.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    val playerId = playerGateway.findByName(playerName)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.entry.player-not-found"),
                            mapOf("player" to playerName),
                        )
                    when (val result = lifecycle.entry(matchId, playerId, null)) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ),
            // astera-match phase <matchId> <triggerName>
            CommandSpec.Literal(
                name = "phase",
                description = MessageKey("astera.match.command.phase"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                    CommandSpec.Argument(
                        name = "trigger",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val triggerName = ctx.required<String>("trigger")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.phase.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    val trigger = parseTrigger(triggerName)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.phase.invalid-trigger"),
                            mapOf("trigger" to triggerName),
                        )
                    when (val result = lifecycle.switchPhase(matchId, trigger)) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.phase"),
            ),
            // astera-match list
            CommandSpec.Literal(
                name = "list",
                description = MessageKey("astera.match.command.list"),
                executor = { ctx ->
                    val active = matches.findActiveMatches()
                    if (active.isEmpty()) {
                        CommandResult.Failure(MessageKey("astera.match.command.list.none"))
                    } else {
                        val senderId = ctx.sender
                        active.forEach { m ->
                            val summary = "${m.id.value} [${m.phase}] stage=${m.stageId.value}"
                            if (senderId != null) {
                                playerGateway.sendMessage(senderId, summary)
                            }
                        }
                        CommandResult.Success
                    }
                },
            ),
        ),
    )

    private fun parseTrigger(name: String): MatchStateMachine.TransitionTrigger? =
        when (name.lowercase()) {
            "countdowncomplete" -> MatchStateMachine.TransitionTrigger.CountdownComplete
            "scorethresholdreached" -> MatchStateMachine.TransitionTrigger.ScoreThresholdReached
            "suddendeathtimer" -> MatchStateMachine.TransitionTrigger.SuddenDeathTimer
            "cancel" -> MatchStateMachine.TransitionTrigger.Cancel
            "adminend" -> MatchStateMachine.TransitionTrigger.AdminEnd
            else -> null
        }
}

private fun StartMatchError.toCommandFailure(): CommandResult.Failure =
    CommandResult.Failure(messageKey)

private fun EndMatchError.toCommandFailure(): CommandResult.Failure =
    CommandResult.Failure(messageKey)

private fun EntryGameError.toCommandFailure(): CommandResult.Failure =
    CommandResult.Failure(messageKey)

private fun SwitchPhaseError.toCommandFailure(): CommandResult.Failure =
    CommandResult.Failure(messageKey)
