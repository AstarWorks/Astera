package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.service.tutorial.TutorialGuide
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId

/**
 * Wave 5p tutorial sequencer Brigadier command spec (replaces deleted
 * Wave 5h `TutorialCommandSpec` which referenced removed RunTutorialUseCase /
 * AdvanceTutorialStepUseCase / ReplayTutorialUseCase / SkipTutorialUseCase).
 *
 *   /astera tutorial start <scriptId>
 *   /astera tutorial skip
 *   /astera tutorial status
 *
 * Permission `astera.tutorial.use` (player-defaultable).
 */
public fun buildTutorialCommandSpec(
    sequencer: ITutorialSequencerService,
    scriptIds: () -> List<String> = { emptyList() },
    savedState: (PlayerId) -> TutorialRunnerSnapshot? = { null },
): CommandSpec.Literal {

    val start = CommandSpec.Literal(
        name = "start",
        subcommands = listOf(
            CommandSpec.Argument("script-id", ArgumentType.StringArg(), completer = { _ -> scriptIds() }, optional = true),
        ),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            val scriptIdRaw = ctx.optional<String>("script-id") ?: "getting-started"
            val scriptId = runCatching { TutorialScriptId(scriptIdRaw) }.getOrNull()
                ?: return@lambda CommandResult.Failure(MessageKey("astera.tutorial.error"), mapOf("error" to scriptIdRaw))
            when (val r = sequencer.startSequence(playerId, scriptId)) {
                is Result.Success -> {
                    ctx.reply(MessageKey("astera.command.reply.tutorial-start"), mapOf("script" to scriptIdRaw))
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    MessageKey("astera.tutorial.error"),
                    mapOf("error" to r.error.toString()),
                )
            }
        },
    ).forEveryone(Permission("astera.tutorial.use"))

    val skip = CommandSpec.Literal(
        name = "skip",
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = sequencer.cancel(playerId)) {
                is Result.Success -> {
                    ctx.reply(MessageKey("astera.tutorial.guide.status.paused"),
                        mapOf("step" to ((savedState(playerId)?.stepIndex ?: 0) + 1).toString()))
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    MessageKey("astera.tutorial.error"),
                    mapOf("error" to r.error.toString()),
                )
            }
        },
    ).forEveryone(Permission("astera.tutorial.use"))

    val status = CommandSpec.Literal(
        name = "status",
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            val saved = savedState(playerId)
            if (saved?.paused == true || saved?.completed == true) {
                val state = checkNotNull(saved)
                ctx.reply(MessageKey(if (state.completed) "astera.tutorial.guide.status.completed" else "astera.tutorial.guide.status.paused"),
                    mapOf("step" to (state.stepIndex + 1).toString()))
                return@lambda CommandResult.Success
            }
            val step = sequencer.currentStep(playerId)
            if (step == null) {
                CommandResult.Failure(MessageKey("astera.tutorial.no_active"))
            } else {
                val action = (step as? TutorialStep.WaitForAction)?.actionId
                if (action in TutorialGuide.ACTIONS) {
                    val token = checkNotNull(action)
                    ctx.reply(TutorialGuide.key(token, "subtitle"))
                    ctx.reply(TutorialGuide.key(token, "hint"))
                } else {
                    ctx.reply(MessageKey("astera.command.reply.tutorial-status"), mapOf("step" to (saved?.stepIndex?.plus(1)?.toString() ?: step::class.simpleName.orEmpty())))
                }
                CommandResult.Success
            }
        },
    ).forEveryone(Permission("astera.tutorial.use"))

    return CommandSpec.Literal(
        name = "tutorial",
        subcommands = listOf(start, skip, status),
    )
}
