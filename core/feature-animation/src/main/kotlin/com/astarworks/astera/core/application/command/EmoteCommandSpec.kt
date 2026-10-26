package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission

/**
 * Skin-rig emotes (ADR-0250):
 *
 *   /astera emote <id>      play `content/animations/<id>.yaml` on yourself
 *   /astera emote stop      blend the running emote out (the only way to end a looping one)
 *
 * Permission `astera.emote.use` (player-defaultable). `animator` is resolved per call so the Paper
 * overlay registered after the command tree is picked up.
 */
public fun buildEmoteCommandSpec(
    animator: () -> ISkinRigAnimator?,
    animationIds: () -> List<String>,
): CommandSpec.Literal = CommandSpec.Literal(
    name = "emote",
    subcommands = listOf(
        CommandSpec.Argument("id", ArgumentType.StringArg(), completer = { _ -> animationIds() + STOP }),
    ),
    executor = lambda@{ ctx ->
        val playerId = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
        val rig = animator() ?: return@lambda CommandResult.Failure(MessageKey("astera.command.reply.emote-unavailable"))
        val raw = ctx.required<String>("id")
        if (raw == STOP) {
            return@lambda when (rig.stop(playerId)) {
                is Result.Success -> {
                    ctx.reply(MessageKey("astera.command.reply.emote-stop"))
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(MessageKey("astera.command.reply.emote-not-playing"))
            }
        }
        val id = runCatching { CustomAnimationId(raw) }.getOrNull()
            ?: return@lambda CommandResult.Failure(MessageKey("astera.command.reply.emote-unknown"), mapOf("emote" to raw))
        when (val result = rig.play(playerId, id)) {
            is Result.Success -> {
                ctx.reply(MessageKey("astera.command.reply.emote-play"), mapOf("emote" to raw))
                CommandResult.Success
            }
            is Result.Failure -> when (result.error) {
                is SkinRigError.UnknownAnimation ->
                    CommandResult.Failure(MessageKey("astera.command.reply.emote-unknown"), mapOf("emote" to raw))
                else -> CommandResult.Failure(MessageKey("astera.command.reply.emote-failed"), mapOf("error" to result.error.toString()))
            }
        }
    },
).forEveryone(Permission("astera.emote.use"))

private const val STOP = "stop"
