package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
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
 *   /astera tutorial advance
 *   /astera tutorial cancel
 *   /astera tutorial status
 *
 * Permission `astera.tutorial.use` (player-defaultable).
 */
public fun buildTutorialCommandSpec(
    sequencer: ITutorialSequencerService,
    scriptIds: () -> List<String> = { emptyList() },
): CommandSpec.Literal {

    fun playerOnly(executorPlayerIdNull: Boolean, body: () -> CommandResult): CommandResult =
        if (executorPlayerIdNull) {
            CommandResult.Failure(MessageKey("astera.command.player-only"))
        } else {
            body()
        }

    val start = CommandSpec.Literal(
        name = "start",
        subcommands = listOf(
            CommandSpec.Argument("script-id", ArgumentType.StringArg(), completer = { _ -> scriptIds() }),
        ),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            val scriptIdRaw = ctx.required<String>("script-id")
            when (val r = sequencer.startSequence(playerId, TutorialScriptId(scriptIdRaw))) {
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

    val advance = CommandSpec.Literal(
        name = "advance",
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = sequencer.advance(playerId)) {
                is Result.Success -> {
                    ctx.reply(MessageKey("astera.command.reply.tutorial-advance"))
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    MessageKey("astera.tutorial.error"),
                    mapOf("error" to r.error.toString()),
                )
            }
        },
    ).forEveryone(Permission("astera.tutorial.use"))

    val cancel = CommandSpec.Literal(
        name = "cancel",
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = sequencer.cancel(playerId)) {
                is Result.Success -> {
                    ctx.reply(MessageKey("astera.command.reply.tutorial-cancel"))
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
            val step = sequencer.currentStep(playerId)
            if (step == null) {
                CommandResult.Failure(MessageKey("astera.tutorial.no_active"))
            } else {
                ctx.reply(MessageKey("astera.command.reply.tutorial-status"), mapOf("step" to step::class.simpleName.orEmpty()))
                CommandResult.Success
            }
        },
    ).forEveryone(Permission("astera.tutorial.use"))

    return CommandSpec.Literal(
        name = "tutorial",
        subcommands = listOf(start, advance, cancel, status),
    )
}
