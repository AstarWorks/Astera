package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
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
): CommandSpec.Literal {

    fun playerOnly(executorPlayerIdNull: Boolean, body: () -> CommandResult): CommandResult =
        if (executorPlayerIdNull) {
            CommandResult.Failure(MessageKey("astera.command.player-only"))
        } else {
            body()
        }

    val start = CommandSpec.Literal(
        name = "start",
        description = MessageKey("astera.tutorial.command.start"),
        subcommands = listOf(
            CommandSpec.Argument("scriptId", ArgumentType.StringArg()),
        ),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            val scriptIdRaw = ctx.required<String>("scriptId")
            when (val r = sequencer.startSequence(playerId, TutorialScriptId(scriptIdRaw))) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(
                    MessageKey("astera.tutorial.error"),
                    mapOf("error" to r.error.toString()),
                )
            }
        },
    )

    val advance = CommandSpec.Literal(
        name = "advance",
        description = MessageKey("astera.tutorial.command.advance"),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = sequencer.advance(playerId)) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(
                    MessageKey("astera.tutorial.error"),
                    mapOf("error" to r.error.toString()),
                )
            }
        },
    )

    val cancel = CommandSpec.Literal(
        name = "cancel",
        description = MessageKey("astera.tutorial.command.cancel"),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = sequencer.cancel(playerId)) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(
                    MessageKey("astera.tutorial.error"),
                    mapOf("error" to r.error.toString()),
                )
            }
        },
    )

    val status = CommandSpec.Literal(
        name = "status",
        description = MessageKey("astera.tutorial.command.status"),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            val step = sequencer.currentStep(playerId)
            if (step == null) {
                CommandResult.Failure(MessageKey("astera.tutorial.no_active"))
            } else {
                CommandResult.Success
            }
        },
    )

    return CommandSpec.Literal(
        name = "tutorial",
        description = MessageKey("astera.tutorial.command.root"),
        subcommands = listOf(start, advance, cancel, status),
    )
}
