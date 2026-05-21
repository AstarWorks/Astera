package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.usecase.tutorial.ReplayTutorialUseCase
import com.astarworks.astera.core.application.usecase.tutorial.SkipTutorialUseCase
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId

/**
 * Wave 5h (ADR-0079 §3) — builds the `astera tutorial` subcommand tree.
 *
 * Two subcommands ship in Phase 1:
 *
 * ```
 * /astera tutorial
 *   skip   <tutorial-id>     (permission: astera.tutorial.skip,   PLAYER role)
 *   replay <tutorial-id>     (permission: astera.tutorial.replay, PLAYER role)
 * ```
 *
 * `skip` and `replay` resolve a [TutorialScriptId] and call
 * [SkipTutorialUseCase] / [ReplayTutorialUseCase] respectively. Console
 * invocations return `astera.command.player-only` since both actions only
 * make sense for a real player. Invalid (non-kebab-case) ids surface as
 * `astera.tutorial.command.invalid_id`.
 *
 * Returned as a [CommandSpec.Literal] so Phase C wiring can fold it into the
 * top-level `/astera` tree alongside `/astera reload` and the other Core
 * subcommands.
 */
public fun buildTutorialCommandSpec(
    skip: SkipTutorialUseCase,
    replay: ReplayTutorialUseCase,
    permService: IPermissionService,
): CommandSpec.Literal {
    val skipLiteral = CommandSpec.Literal(
        name = "skip",
        description = MessageKey("astera.tutorial.command.skip"),
        subcommands = listOf(
            CommandSpec.Argument("tutorial-id", ArgumentType.StringArg()),
        ),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender
                ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val raw = ctx.required<String>("tutorial-id")
            val tutorialId = runCatching { TutorialScriptId(raw) }.getOrNull()
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.tutorial.command.invalid_id"),
                    mapOf("tutorial-id" to raw),
                )

            skip.skip(playerId, tutorialId)
            CommandResult.Success
        },
    ).requirePermission(
        permissionService = permService,
        permission = Permission("astera.tutorial.skip"),
    )

    val replayLiteral = CommandSpec.Literal(
        name = "replay",
        description = MessageKey("astera.tutorial.command.replay"),
        subcommands = listOf(
            CommandSpec.Argument("tutorial-id", ArgumentType.StringArg()),
        ),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender
                ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val raw = ctx.required<String>("tutorial-id")
            val tutorialId = runCatching { TutorialScriptId(raw) }.getOrNull()
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.tutorial.command.invalid_id"),
                    mapOf("tutorial-id" to raw),
                )

            replay.replay(playerId, tutorialId)
            CommandResult.Success
        },
    ).requirePermission(
        permissionService = permService,
        permission = Permission("astera.tutorial.replay"),
    )

    return CommandSpec.Literal(
        name = "tutorial",
        description = MessageKey("astera.tutorial.command.root"),
        subcommands = listOf(skipLiteral, replayLiteral),
    )
}
