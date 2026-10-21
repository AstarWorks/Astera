package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.application.port.inbound.IMatchRecorder
import com.astarworks.astera.match.application.port.outbound.IMatchReplayRepository
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.application.usecase.match.EndMatchError
import com.astarworks.astera.match.application.usecase.match.EntryGameError
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseError
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.core.domain.replay.ReplayFormat
import com.astarworks.astera.core.domain.stage.StageId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.logging.Logger

/**
 * `/astera-match`, `/astera-queue` command trees (ADR-0174: split out of
 * [MatchWiring] for size; pure `CommandSpec`s, host-neutral).
 */
internal object MatchCommandSpecs {

    /**
     * Wave 5w (ADR-0089 δ): `/astera queue join <mode>` / `leave` / `status`.
     *
     * The mode argument accepts:
     *   - `norma` — `MatchmakingMode.Norma`
     *   - `custom:<game-mode-id>` — `MatchmakingMode.Custom`
     *   - `tournament:<id>` — `MatchmakingMode.Tournament`
     *
     * Wave 7b (ADR-0037) replaces this with a richer subcommand tree once
     * tournament brackets and rating-based modes land.
     */
    fun buildQueueCommandSpec(
        matchmaker: IMatchmaker,
        playerGateway: IPlayerGateway,
        permService: IPermissionService,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-queue",
        description = MessageKey("astera.match.matchmaking.enqueue.success"),
        subcommands = listOf(
            CommandSpec.Literal(
                name = "join",
                description = MessageKey("astera.match.matchmaking.enqueue.success"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "mode",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val senderId = ctx.sender
                        ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                    val modeStr = ctx.required<String>("mode")
                    val mode = parseMatchmakingMode(modeStr)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.matchmaking.enqueue.invalid-mode"),
                            mapOf("mode" to modeStr),
                        )
                    when (val res = matchmaker.enqueue(senderId, mode, partyId = null)) {
                        is Result.Success -> {
                            playerGateway.sendMessage(senderId, "Joined queue: $modeStr")
                            CommandResult.Success
                        }
                        is Result.Failure -> {
                            val key = when (res.error) {
                                is com.astarworks.astera.match.domain.matchmaking.QueueError.AlreadyEnqueued ->
                                    MessageKey("astera.match.matchmaking.enqueue.already-enqueued")
                                is com.astarworks.astera.match.domain.matchmaking.QueueError.ModeClosed ->
                                    MessageKey("astera.match.matchmaking.enqueue.mode-closed")
                                else -> MessageKey("astera.match.matchmaking.queue-failure")
                            }
                            CommandResult.Failure(key)
                        }
                    }
                },
            ),
            CommandSpec.Literal(
                name = "leave",
                description = MessageKey("astera.match.matchmaking.dequeue.success"),
                executor = { ctx ->
                    val senderId = ctx.sender
                        ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                    when (val res = matchmaker.dequeue(senderId)) {
                        is Result.Success -> {
                            playerGateway.sendMessage(senderId, "Left the queue.")
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(
                            MessageKey("astera.match.matchmaking.dequeue.not-enqueued"),
                        )
                    }
                },
            ),
            CommandSpec.Literal(
                name = "status",
                description = MessageKey("astera.match.matchmaking.status.line"),
                executor = { ctx ->
                    val senderId = ctx.sender
                        ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                    val status = matchmaker.queueStatus(senderId)
                    if (status == null) {
                        CommandResult.Failure(MessageKey("astera.match.matchmaking.status.none"))
                    } else {
                        playerGateway.sendMessage(senderId, "In queue: ${status.mode}")
                        CommandResult.Success
                    }
                },
            ),
        ),
    ).requirePermission(
        permissionService = permService,
        permission = Permission("astera.match.queue"),
    )

    /** Parse `"norma"` / `"custom:break-core"` / `"tournament:cup-2026"` into [MatchmakingMode]. */
    private fun parseMatchmakingMode(raw: String): MatchmakingMode? {
        val lower = raw.lowercase()
        if (lower == "norma") return MatchmakingMode.Norma
        if (lower.startsWith("custom:")) {
            val gmId = raw.substringAfter(":").takeIf { it.isNotBlank() } ?: return null
            return runCatching { MatchmakingMode.Custom(GameModeId(gmId)) }.getOrNull()
        }
        if (lower.startsWith("tournament:")) {
            val tid = raw.substringAfter(":").takeIf { it.isNotBlank() } ?: return null
            return runCatching { MatchmakingMode.Tournament(tid) }.getOrNull()
        }
        return null
    }

    fun buildMatchCommandSpec(
        lifecycle: IMatchLifecycle,
        matches: IMatchRepository,
        playerGateway: IPlayerGateway,
        permService: IPermissionService,
        recorder: IMatchRecorder,
        replayRepo: IMatchReplayRepository,
        exportRoot: Path,
        logger: Logger,
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
            // astera-match abort <matchId> [reason]  (Wave 5complete, ADR-0117 §Axis 3)
            //   AdminEnded the match immediately. Reason is free-form for
            //   audit (defaults to "abort").
            CommandSpec.Literal(
                name = "abort",
                description = MessageKey("astera.match.command.abort"),
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
                permission = Permission("astera.match.admin.abort"),
            ),
            // astera-match cancel <matchId>  (Wave 5complete, ADR-0117 §Axis 3)
            //   End the match with a Cancelled reason so reward distribution +
            //   audit can branch on "this match never had a winner".
            CommandSpec.Literal(
                name = "cancel",
                description = MessageKey("astera.match.command.cancel"),
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
                    when (val result = lifecycle.end(matchId, EndReason.Cancelled("admin-cancel"))) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.cancel"),
            ),
            // astera-match restart <matchId>  (Wave 5complete, ADR-0117 §Axis 3)
            //   End the current match + start a new one on the same stage.
            //   The two-step is best-effort: if start fails the players are
            //   already booted from the prior match (TeleportToLobbyOnMatchEnded
            //   handles teleport-out), so they land in lobby and can re-queue.
            CommandSpec.Literal(
                name = "restart",
                description = MessageKey("astera.match.command.restart"),
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
                    val match = matches.findById(matchId)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.error.match-not-found"),
                            mapOf("match" to matchIdStr),
                        )
                    val savedStage = match.stageId
                    val endOutcome = lifecycle.end(matchId, EndReason.AdminEnded)
                    if (endOutcome is Result.Failure) return@Literal endOutcome.error.toCommandFailure()
                    when (val startOutcome = lifecycle.start(savedStage)) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> startOutcome.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.restart"),
            ),
            // astera-match replay <start|stop|export> <matchId> (Wave 5v / ADR-0088)
            buildReplayCommandSpec(recorder, replayRepo, permService, exportRoot, playerGateway, logger),
        ),
    )

    /**
     * Wave 5v: `/astera-match replay <subcommand>` admin commands. Three
     * subcommands: `start <matchId>` begins capture under the default
     * `EventLogKeyFrame` format; `stop <matchId>` finalises and persists;
     * `export <matchId>` writes the most recent persisted replay for the
     * match to a `.replay.json` file under the plugin's data folder.
     *
     * Permissions:
     *  - `astera.match.admin.replay.start`
     *  - `astera.match.admin.replay.stop`
     *  - `astera.match.admin.replay.export`
     */
    private fun buildReplayCommandSpec(
        recorder: IMatchRecorder,
        replayRepo: IMatchReplayRepository,
        permService: IPermissionService,
        exportRoot: Path,
        playerGateway: IPlayerGateway,
        logger: Logger,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "replay",
        description = MessageKey("astera.match.command.replay"),
        subcommands = listOf(
            CommandSpec.Literal(
                name = "start",
                description = MessageKey("astera.match.command.replay.start"),
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
                            MessageKey("astera.match.command.replay.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    recorder.startRecording(matchId, ReplayFormat.EventLogKeyFrame())
                    val senderId = ctx.sender
                    if (senderId != null) {
                        playerGateway.sendMessage(senderId, "Recording started for $matchIdStr")
                    }
                    CommandResult.Success
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.replay.start"),
            ),
            CommandSpec.Literal(
                name = "stop",
                description = MessageKey("astera.match.command.replay.stop"),
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
                            MessageKey("astera.match.command.replay.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    when (val result = recorder.stopRecording(matchId)) {
                        is Result.Success -> {
                            val senderId = ctx.sender
                            if (senderId != null) {
                                playerGateway.sendMessage(
                                    senderId,
                                    "Recording stopped: events=${result.value.events.size} " +
                                        "keyFrames=${result.value.keyFrames.size} " +
                                        "sizeBytes=${result.value.sizeBytes}",
                                )
                            }
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(result.error.messageKey)
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.replay.stop"),
            ),
            CommandSpec.Literal(
                name = "export",
                description = MessageKey("astera.match.command.replay.export"),
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
                            MessageKey("astera.match.command.replay.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    val replay = replayRepo.findByMatchId(matchId)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.replay.not-found"),
                            mapOf("match" to matchIdStr),
                        )
                    val outPath = try {
                        exportReplayToFile(replay, exportRoot)
                    } catch (t: Throwable) {
                        logger.severe("Replay export failed for $matchIdStr: ${t.message}")
                        return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.replay.export-failed"),
                            mapOf("match" to matchIdStr, "reason" to (t.message ?: "unknown")),
                        )
                    }
                    val senderId = ctx.sender
                    if (senderId != null) {
                        playerGateway.sendMessage(senderId, "Replay exported to $outPath")
                    }
                    CommandResult.Success
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.replay.export"),
            ),
        ),
    )

    /**
     * Serialise [replay] into the stable `MatchReplayJson` wire shape and
     * write it under [exportRoot] as `<matchId>.replay.json`. Returns the
     * absolute path written.
     */
    private fun exportReplayToFile(
        replay: com.astarworks.astera.core.domain.replay.RecordedReplay,
        exportRoot: Path,
    ): Path {
        Files.createDirectories(exportRoot)
        val outFile = exportRoot.resolve("${replay.matchId.value}.replay.json")
        val exportEnvelope = com.astarworks.astera.match.application.replay.json.ReplayExportEnvelope(
            id = replay.id.uuid.toString(),
            matchId = replay.matchId.value,
            startTick = replay.startTick,
            endTick = replay.endTick,
            sizeBytes = replay.sizeBytes,
            format = com.astarworks.astera.match.application.replay.json.ReplayFormatJson.fromDomain(replay.format),
            keyFrames = com.astarworks.astera.match.application.replay.json.KeyFramesJson.fromDomain(replay.keyFrames),
            events = com.astarworks.astera.match.application.replay.json.ReplayEventsJson.fromDomain(replay.events),
        )
        val json = Json {
            prettyPrint = false
            encodeDefaults = true
            classDiscriminator = "type"
        }
        val payload = json.encodeToString(exportEnvelope)
        Files.write(
            outFile,
            payload.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        return outFile.toAbsolutePath()
    }

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
