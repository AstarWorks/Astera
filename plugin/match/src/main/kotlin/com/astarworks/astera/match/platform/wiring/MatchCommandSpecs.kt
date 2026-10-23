package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandConfirm
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.forEveryone
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
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueError
import com.astarworks.astera.match.domain.matchmaking.QueueLane
import com.astarworks.astera.match.domain.matchmaking.QueueSettings
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.NormaLevelService
import com.astarworks.astera.match.application.usecase.match.EndMatchError
import com.astarworks.astera.match.domain.norma.NormaError
import com.astarworks.astera.match.domain.norma.NormaGoal
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
 * `/astera match ...`, `/astera queue ...` command trees (ADR-0174: split out of
 * [MatchWiring] for size; pure `CommandSpec`s, host-neutral; ADR-0184: mounted
 * under the single `/astera` root by the wiring).
 */
internal object MatchCommandSpecs {

    /**
     * `/astera norma choose star|kill`: picks which goal clears the player's current Norma level (stars carried or
     * kills made). Allowed any time before the goal is reached; refused outside a live Norma match.
     */
    fun buildNormaCommandSpec(norma: NormaLevelService): CommandSpec.Literal = CommandSpec.Literal(
        name = "norma",
        subcommands = listOf(
            CommandSpec.Literal(
                name = "choose",
                subcommands = listOf(
                    CommandSpec.Argument("goal", ArgumentType.Choice(listOf("star", "kill"))),
                ),
                executor = { ctx ->
                    val senderId = ctx.sender
                        ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                    val goal = if (ctx.required<String>("goal").equals("kill", ignoreCase = true)) NormaGoal.KILL else NormaGoal.STAR
                    when (val res = norma.choose(senderId, goal)) {
                        is Result.Success -> {
                            ctx.reply(MessageKey("astera.match.norma-choice.chosen"), mapOf("goal" to goal.name.lowercase()))
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(
                            when (res.error) {
                                NormaError.NotInNormaMatch -> MessageKey("astera.match.norma-choice.not-in-match")
                                NormaError.AtMaxLevel -> MessageKey("astera.match.norma-choice.max-level")
                            },
                        )
                    }
                },
            ).forEveryone(Permission("astera.norma.choose")),
        ),
    )

    /**
     * `/astera queue join <lane>` / `leave` / `status` (Wave 5w ADR-0089 delta, WP8).
     *
     * The lane argument is a lane key of `queue.yaml` (`norma`, `annihilation`, ...) or the
     * [MatchmakingMode.parse] wire form: `norma`, `custom:<game-mode-id>` or `tournament:<id>`. Players already in a running match cannot queue.
     * Replies use the `astera.match.queue.*` keys shared with the lobby GUI.
     */
    fun buildQueueCommandSpec(
        matchmaker: IMatchmaker,
        matches: IMatchRepository,
        settings: () -> QueueSettings,
    ): CommandSpec.Literal {
        return CommandSpec.Literal(
            name = "queue",
            subcommands = listOf(
                CommandSpec.Literal(
                    name = "join",
                    subcommands = listOf(
                        CommandSpec.Argument(
                            name = "lane",
                            type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                            completer = { _ -> laneTokens(settings()) },
                            // hover text next to each lane: players waiting / players needed to start
                            tooltips = { _ ->
                                settings().lanes.mapValues { (_, lane) ->
                                    "${matchmaker.pendingByMode(lane.mode).size}/${lane.minPlayers}"
                                }
                            },
                        ),
                    ),
                    executor = { ctx ->
                        val senderId = ctx.sender
                            ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                        val modeStr = ctx.required<String>("lane")
                        val mode = parseLane(modeStr, settings())
                            ?: return@Literal CommandResult.Failure(
                                MessageKey("astera.match.queue.unknown-mode"),
                                mapOf("mode" to modeStr),
                            )
                        if (matches.findActiveMatches().any { m -> m.teams.any { senderId in it.members } }) {
                            return@Literal CommandResult.Failure(MessageKey("astera.match.queue.join.in-match"))
                        }
                        when (val res = matchmaker.enqueue(senderId, mode, partyId = null)) {
                            is Result.Success -> {
                                ctx.reply(MessageKey("astera.match.queue.join.success"), mapOf("mode" to modeStr))
                                replyProgress(ctx, mode, matchmaker, settings)
                                CommandResult.Success
                            }
                            is Result.Failure -> CommandResult.Failure(
                                when (res.error) {
                                    is QueueError.AlreadyEnqueued -> MessageKey("astera.match.queue.join.already-queued")
                                    is QueueError.ModeClosed -> MessageKey("astera.match.queue.join.mode-closed")
                                    is QueueError.PartyTooLargeForMode -> MessageKey("astera.match.queue.join.party-too-large")
                                    is QueueError.NotEnqueued -> MessageKey("astera.match.queue.join.not-queued")
                                },
                            )
                        }
                    },
                ).forEveryone(Permission("astera.queue.join")),
                CommandSpec.Literal(
                    name = "leave",
                    executor = { ctx ->
                        val senderId = ctx.sender
                            ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                        when (matchmaker.dequeue(senderId)) {
                            is Result.Success -> {
                                ctx.reply(MessageKey("astera.match.queue.dequeue.success"))
                                CommandResult.Success
                            }
                            is Result.Failure -> CommandResult.Failure(
                                MessageKey("astera.match.queue.dequeue.not-enqueued"),
                            )
                        }
                    },
                ).forEveryone(Permission("astera.queue.leave")),
                CommandSpec.Literal(
                    name = "status",
                    executor = { ctx ->
                        val senderId = ctx.sender
                            ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                        val status = matchmaker.queueStatus(senderId)
                        if (status == null) {
                            CommandResult.Failure(MessageKey("astera.match.queue.status.none"))
                        } else {
                            val position = matchmaker.pendingByMode(status.mode)
                                .indexOfFirst { it.playerId == senderId } + 1
                            ctx.reply(
                                MessageKey("astera.match.queue.status.line"),
                                mapOf("mode" to status.mode.toString(), "position" to position.toString()),
                            )
                            replyProgress(ctx, status.mode, matchmaker, settings)
                            ctx.reply(MessageKey("astera.match.queue.status.actions")) // clickable [Leave]
                            CommandResult.Success
                        }
                    },
                ).forEveryone(Permission("astera.queue.status")),
            ),
        )
    }

    /** "n/min players queued" line so a lone playtester sees what the lane is waiting for. */
    private fun replyProgress(
        ctx: CommandContext,
        mode: MatchmakingMode,
        matchmaker: IMatchmaker,
        settings: () -> QueueSettings,
    ) {
        val lane = settings().laneFor(mode) ?: return
        ctx.reply(
            MessageKey("astera.match.queue.status.progress"),
            mapOf(
                "players" to matchmaker.pendingByMode(mode).size.toString(),
                "required" to lane.minPlayers.toString(),
            ),
        )
    }

    /** The lane keys of `content/matchmaking/queue.yaml` (`norma`, `annihilation`, ...): what `queue join` suggests. */
    private fun laneTokens(settings: QueueSettings): List<String> = settings.lanes.keys.toList()

    /**
     * A lane key (`annihilation`) or the wire form (`norma`, `custom:<id>`, `tournament:<id>`) the lobby GUI
     * uses. The bare key is what the playtest guide and tab completion show.
     */
    private fun parseLane(raw: String, settings: QueueSettings): MatchmakingMode? =
        MatchmakingMode.parse(raw) ?: settings.lanes[raw.lowercase()]?.mode

    fun buildMatchCommandSpec(
        lifecycle: IMatchLifecycle,
        matches: IMatchRepository,
        playerGateway: IPlayerGateway,
        recorder: IMatchRecorder,
        replayRepo: IMatchReplayRepository,
        exportRoot: Path,
        logger: Logger,
        stageIds: () -> List<String> = { emptyList() },
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "match",
        subcommands = listOf(
            // /astera match start <stageId>
            CommandSpec.Literal(
                name = "start",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "stage-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> stageIds() },
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
                            ctx.reply(MessageKey("astera.command.reply.match-start"), mapOf("match" to result.value.value))
                            CommandResult.Success
                        }
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(Permission("astera.match.admin.start")),
            // /astera match end <matchId>
            CommandSpec.Literal(
                name = "end",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> matches.findActiveMatches().map { it.id.value } },
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
                        is Result.Success -> {
                            ctx.reply(MessageKey("astera.command.reply.match-end"), mapOf("match" to matchIdStr))
                            CommandResult.Success
                        }
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(Permission("astera.match.admin.end")),
            // /astera match entry <matchId> <playerName>
            CommandSpec.Literal(
                name = "entry",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> matches.findActiveMatches().map { it.id.value } },
                    ),
                    CommandSpec.Argument(
                        name = "player",
                        type = ArgumentType.PlayerName,
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
                        is Result.Success -> {
                            ctx.reply(
                                MessageKey("astera.command.reply.match-entry"),
                                mapOf("match" to matchIdStr, "player" to playerName),
                            )
                            CommandResult.Success
                        }
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(Permission("astera.match.admin.entry")),
            // /astera match phase <matchId> <triggerName>
            CommandSpec.Literal(
                name = "phase",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> matches.findActiveMatches().map { it.id.value } },
                    ),
                    CommandSpec.Argument(
                        name = "trigger",
                        type = ArgumentType.Choice(
                            "countdowncomplete", "scorethresholdreached", "suddendeathtimer", "cancel", "adminend",
                        ),
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
                        is Result.Success -> {
                            ctx.reply(
                                MessageKey("astera.command.reply.match-phase"),
                                mapOf("match" to matchIdStr, "trigger" to triggerName),
                            )
                            CommandResult.Success
                        }
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(Permission("astera.match.admin.phase")),
            // /astera match list
            CommandSpec.Literal(
                name = "list",
                executor = { ctx ->
                    val active = matches.findActiveMatches()
                    if (active.isEmpty()) {
                        ctx.reply(MessageKey("astera.command.reply.match-list-none"))
                    } else {
                        ctx.reply(MessageKey("astera.command.reply.match-list-header"), mapOf("count" to active.size.toString()))
                        // operators get clickable [End] / [Abort] buttons on each line
                        val lineKey = if (ctx.actor.isOperator) "astera.command.reply.match-list-line-op" else "astera.command.reply.match-list-line"
                        active.forEach { m ->
                            ctx.reply(
                                MessageKey(lineKey),
                                mapOf("match" to m.id.value, "phase" to m.phase.toString(), "stage" to m.stageId.value),
                            )
                        }
                    }
                    CommandResult.Success
                },
            ).forEveryone(Permission("astera.match.list")),
            // /astera match abort <matchId> [reason]  (Wave 5complete, ADR-0117 §Axis 3)
            //   AdminEnded the match immediately. Reason is free-form for
            //   audit (defaults to "abort").
            CommandSpec.Literal(
                name = "abort",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> matches.findActiveMatches().map { it.id.value } },
                    ),
                    CommandConfirm.argument(),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    CommandConfirm.guard(ctx, "/astera match abort $matchIdStr")?.let { return@Literal it }
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.end.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    when (val result = lifecycle.end(matchId, EndReason.AdminEnded)) {
                        is Result.Success -> {
                            ctx.reply(MessageKey("astera.command.reply.match-abort"), mapOf("match" to matchIdStr))
                            CommandResult.Success
                        }
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(Permission("astera.match.admin.abort")),
            // /astera match cancel <matchId>  (Wave 5complete, ADR-0117 §Axis 3)
            //   End the match with a Cancelled reason so reward distribution +
            //   audit can branch on "this match never had a winner".
            CommandSpec.Literal(
                name = "cancel",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> matches.findActiveMatches().map { it.id.value } },
                    ),
                    CommandConfirm.argument(),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    CommandConfirm.guard(ctx, "/astera match cancel $matchIdStr")?.let { return@Literal it }
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.end.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    when (val result = lifecycle.end(matchId, EndReason.Cancelled("admin-cancel"))) {
                        is Result.Success -> {
                            ctx.reply(MessageKey("astera.command.reply.match-cancel"), mapOf("match" to matchIdStr))
                            CommandResult.Success
                        }
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(Permission("astera.match.admin.cancel")),
            // /astera match restart <matchId>  (Wave 5complete, ADR-0117 §Axis 3)
            //   End the current match + start a new one on the same stage.
            //   The two-step is best-effort: if start fails the players are
            //   already booted from the prior match (TeleportToLobbyOnMatchEnded
            //   handles teleport-out), so they land in lobby and can re-queue.
            CommandSpec.Literal(
                name = "restart",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> matches.findActiveMatches().map { it.id.value } },
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
                        is Result.Success -> {
                            ctx.reply(
                                MessageKey("astera.command.reply.match-restart"),
                                mapOf("match" to matchIdStr, "new" to startOutcome.value.value),
                            )
                            CommandResult.Success
                        }
                        is Result.Failure -> startOutcome.error.toCommandFailure()
                    }
                },
            ).requirePermission(Permission("astera.match.admin.restart")),
            // /astera match replay <start|stop|export> <matchId> (Wave 5v / ADR-0088)
            buildReplayCommandSpec(recorder, replayRepo, exportRoot, matches, logger),
        ),
    )

    /**
     * Wave 5v: `/astera match replay <subcommand>` admin commands. Three
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
        exportRoot: Path,
        matches: IMatchRepository,
        logger: Logger,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "replay",
        subcommands = listOf(
            CommandSpec.Literal(
                name = "start",
                                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> matches.findActiveMatches().map { it.id.value } },
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
                    ctx.reply(MessageKey("astera.command.reply.match-replay-start"), mapOf("match" to matchIdStr))
                    CommandResult.Success
                },
            ).requirePermission(Permission("astera.match.admin.replay.start")),
            CommandSpec.Literal(
                name = "stop",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> matches.findActiveMatches().map { it.id.value } },
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
                            ctx.reply(
                                MessageKey("astera.command.reply.match-replay-stop"),
                                mapOf(
                                    "events" to result.value.events.size.toString(),
                                    "keyframes" to result.value.keyFrames.size.toString(),
                                    "bytes" to result.value.sizeBytes.toString(),
                                ),
                            )
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(result.error.messageKey)
                    }
                },
            ).requirePermission(Permission("astera.match.admin.replay.stop")),
            CommandSpec.Literal(
                name = "export",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> matches.findActiveMatches().map { it.id.value } },
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
                    ctx.reply(MessageKey("astera.command.reply.match-replay-export"), mapOf("path" to outPath.toString()))
                    CommandResult.Success
                },
            ).requirePermission(Permission("astera.match.admin.replay.export")),
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
