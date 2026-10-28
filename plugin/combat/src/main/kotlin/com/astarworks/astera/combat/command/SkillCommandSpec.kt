package com.astarworks.astera.combat.command

import com.astarworks.astera.combat.application.admin.AdminSkillCaster
import com.astarworks.astera.combat.application.admin.AdminSkillsPanel
import com.astarworks.astera.combat.application.preview.PreviewError
import com.astarworks.astera.combat.application.preview.PreviewOptions
import com.astarworks.astera.combat.application.preview.PreviewOptionsError
import com.astarworks.astera.combat.application.preview.PreviewView
import com.astarworks.astera.combat.application.preview.SkillPreviewService
import com.astarworks.astera.combat.application.trace.SkillEngineTrace
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * `/astera skill list` and `/astera skill cast <skill-id> [target] [--force]`
 * (F2-5, ADR-0196; mounted by `CombatWiring` under the ADR-0184 command registry).
 *
 * `cast` runs the skill for [target] (default: the sender) through the regular cast
 * pipeline without spending resources; the skill's own cooldown applies unless
 * `--force`. Both are operator-only.
 *
 * `/astera skill preview <skill-id> [view=side|opponent|behind|stay] [speed=1|0.5|0.25] [distance=6]`,
 * `preview again`, `preview freeze|step [n]|unfreeze`: a dummy caster casts the skill in front of the
 * operator, who watches from another viewpoint. Present only when the host supplied [preview].
 */
internal fun skillCommandSpec(
    caster: AdminSkillCaster,
    server: IMcServer,
    preview: SkillPreviewService? = null,
): CommandSpec.Literal =
    CommandSpec.Literal(
        name = "skill",
        subcommands = listOfNotNull(
            listSubcommand(caster),
            castSubcommand(caster, server),
            traceSubcommand(),
            preview?.let { previewSubcommand(caster, it) },
        ),
    )

private val K_PV_STARTED = MessageKey("astera.skill.preview.started")
private val K_PV_SLOW = MessageKey("astera.skill.preview.slow-warning")
private val K_PV_AGAIN = MessageKey("astera.skill.preview.again")
private val K_PV_FROZEN = MessageKey("astera.skill.preview.frozen")
private val K_PV_UNFROZEN = MessageKey("astera.skill.preview.unfrozen")
private val K_PV_STEPPED = MessageKey("astera.skill.preview.stepped")
private val K_PV_NOT_FROZEN = MessageKey("astera.skill.preview.not-frozen")
private val K_PV_NOTHING = MessageKey("astera.skill.preview.nothing-to-repeat")
private val K_PV_NEED_OP = MessageKey("astera.skill.preview.speed-needs-operator")
private val K_PV_NO_VIEWER = MessageKey("astera.skill.preview.viewer-unavailable")
private val K_PV_STAGE = MessageKey("astera.skill.preview.stage-failed")
private val K_PV_BAD_OPTION = MessageKey("astera.skill.preview.bad-option")
private val K_PV_BAD_VALUE = MessageKey("astera.skill.preview.bad-value")
private val PREVIEW_PERMISSION = Permission("astera.skill.preview")
private val K_PLAYER_ONLY = MessageKey("astera.command.player-only")

private fun previewSubcommand(caster: AdminSkillCaster, preview: SkillPreviewService): CommandSpec.Literal =
    CommandSpec.Literal(
        name = "preview",
        subcommands = listOf(
            CommandSpec.Literal(
                name = "again",
                executor = { ctx ->
                    val viewer = ctx.sender ?: return@Literal CommandResult.Failure(K_PLAYER_ONLY)
                    preview.again(viewer, ctx.actor.isOperator).toCommandResult(ctx, K_PV_AGAIN, emptyMap(), null)
                },
            ).requirePermission(PREVIEW_PERMISSION),
            CommandSpec.Literal(
                name = "freeze",
                executor = { ctx ->
                    preview.freeze()
                    ctx.reply(K_PV_FROZEN)
                    CommandResult.Success
                },
            ).requirePermission(PREVIEW_PERMISSION),
            CommandSpec.Literal(
                name = "unfreeze",
                executor = { ctx ->
                    preview.unfreeze()
                    ctx.reply(K_PV_UNFROZEN)
                    CommandResult.Success
                },
            ).requirePermission(PREVIEW_PERMISSION),
            CommandSpec.Literal(
                name = "step",
                subcommands = listOf(
                    CommandSpec.Argument(name = "ticks", type = ArgumentType.IntegerArg(1, SkillPreviewService.MAX_STEP), optional = true),
                ),
                executor = { ctx ->
                    val n = ctx.optional<Int>("ticks") ?: 1
                    preview.step(n).toCommandResult(ctx, K_PV_STEPPED, mapOf("ticks" to n.toString()), null)
                },
            ).requirePermission(PREVIEW_PERMISSION),
            CommandSpec.Argument(
                name = "skill-id",
                type = ArgumentType.ItemIdArg,
                completer = { _ -> caster.all().map { it.id.value } },
            ),
            CommandSpec.Argument(
                name = "options",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy),
                completer = { _ -> PreviewOptions.KEYS + PreviewView.entries.map { "view=${it.key}" } + listOf("speed=0.5", "speed=0.25") },
                optional = true,
            ),
        ),
        executor = { ctx -> runPreview(ctx, preview) },
    ).requirePermission(PREVIEW_PERMISSION)

private fun runPreview(ctx: CommandContext, preview: SkillPreviewService): CommandResult {
    val viewer = ctx.sender ?: return CommandResult.Failure(K_PLAYER_ONLY)
    val raw = ctx.required<String>("skill-id")
    val skill = runCatching { SkillId(raw) }.getOrNull()
        ?: return CommandResult.Failure(K_UNKNOWN, mapOf("skill" to raw))
    val options = when (val parsed = PreviewOptions.parse(ctx.optional<String>("options"))) {
        is Result.Success -> parsed.value
        is Result.Failure -> return when (val e = parsed.error) {
            is PreviewOptionsError.UnknownOption -> CommandResult.Failure(K_PV_BAD_OPTION, mapOf("option" to e.token))
            is PreviewOptionsError.BadValue -> CommandResult.Failure(K_PV_BAD_VALUE, mapOf("key" to e.key, "value" to e.value))
        }
    }
    val started = mapOf("skill" to skill.value, "view" to options.view.key, "speed" to options.speed.toString())
    val result = preview.start(viewer, skill, options, ctx.actor.isOperator).toCommandResult(ctx, K_PV_STARTED, started, skill)
    if (result is CommandResult.Success && options.speed < 1.0) ctx.reply(K_PV_SLOW)
    return result
}

private fun Result<Unit, PreviewError>.toCommandResult(
    ctx: CommandContext,
    okKey: MessageKey,
    okArgs: Map<String, String>,
    skill: SkillId?,
): CommandResult = when (this) {
    is Result.Success -> {
        ctx.reply(okKey, okArgs)
        CommandResult.Success
    }
    is Result.Failure -> when (val e = error) {
        is PreviewError.SkillNotFound -> CommandResult.Failure(K_UNKNOWN, mapOf("skill" to e.skill.value))
        PreviewError.ViewerUnavailable -> CommandResult.Failure(K_PV_NO_VIEWER)
        PreviewError.StageFailed -> CommandResult.Failure(K_PV_STAGE)
        PreviewError.SpeedNeedsOperator -> CommandResult.Failure(K_PV_NEED_OP)
        PreviewError.NothingToRepeat -> CommandResult.Failure(K_PV_NOTHING)
        PreviewError.NotFrozen -> CommandResult.Failure(K_PV_NOT_FROZEN)
        is PreviewError.CastFailed ->
            CommandResult.Failure(AdminSkillsPanel.errorKey(e.error), AdminSkillsPanel.errorArgs(e.error, skill ?: e.skill))
    }
}

private val K_LIST_HEADER = MessageKey("astera.skill.list.header")
private val K_LIST_ENTRY = MessageKey("astera.skill.list.entry")
private val K_CAST_OK = MessageKey("astera.skill.cast.ok")
private val K_NEED_TARGET = MessageKey("astera.skill.cast.need-target")
private val K_NO_PLAYER = MessageKey("astera.skill.cast.no-player")

private fun listSubcommand(caster: AdminSkillCaster): CommandSpec.Literal = CommandSpec.Literal(
    name = "list",
    executor = { ctx ->
        val skills = caster.all()
        ctx.reply(K_LIST_HEADER, mapOf("count" to skills.size.toString()))
        for (s in skills) {
            ctx.reply(
                K_LIST_ENTRY,
                mapOf("id" to s.id.value, "archetype" to s.archetype.name.lowercase(), "cooldown" to s.cooldownTicks.toString()),
            )
        }
        CommandResult.Success
    },
).requirePermission(Permission("astera.skill.list"))

private fun castSubcommand(caster: AdminSkillCaster, server: IMcServer): CommandSpec.Literal = CommandSpec.Literal(
    name = "cast",
    subcommands = listOf(
        CommandSpec.Argument(
            name = "skill-id",
            type = ArgumentType.ItemIdArg,
            completer = { _ -> caster.all().map { it.id.value } },
        ),
        CommandSpec.Argument(name = "target", type = ArgumentType.PlayerName, optional = true),
        CommandSpec.Argument(name = "flag", type = ArgumentType.Choice(listOf(FORCE)), optional = true),
    ),
    executor = { ctx -> runCast(ctx, caster, server) },
).requirePermission(Permission("astera.skill.cast"))

private const val FORCE = "--force"

private fun runCast(ctx: CommandContext, caster: AdminSkillCaster, server: IMcServer): CommandResult {
    val raw = ctx.required<String>("skill-id")
    val skill = runCatching { SkillId(raw) }.getOrNull()
        ?: return CommandResult.Failure(K_UNKNOWN, mapOf("skill" to raw))
    // `cast <skill> --force` (no target) lands in the `target` slot; treat it as the flag.
    val rawTarget = ctx.optional<String>("target")
    val force = ctx.optional<String>("flag") == FORCE || rawTarget == FORCE
    val targetName = rawTarget?.takeIf { it != FORCE }
    val targetId = if (targetName != null) {
        server.findPlayerByName(targetName)?.id
            ?: return CommandResult.Failure(K_NO_PLAYER, mapOf("player" to targetName))
    } else {
        ctx.sender ?: return CommandResult.Failure(K_NEED_TARGET)
    }
    return when (val r = caster.cast(targetId, skill, force)) {
        is Result.Success -> {
            ctx.reply(K_CAST_OK, mapOf("skill" to skill.value, "player" to (targetName ?: "you")))
            CommandResult.Success
        }
        is Result.Failure -> CommandResult.Failure(AdminSkillsPanel.errorKey(r.error), AdminSkillsPanel.errorArgs(r.error, skill))
    }
}

private val K_UNKNOWN = MessageKey("astera.admin.panel.skills.err.unknown")

private val K_TRACE = MessageKey("astera.skill.trace.set")

/** `/astera skill trace on|off`: the skill engine cast lifecycle log (SKL-08), for e2e and debugging. */
private fun traceSubcommand(): CommandSpec.Literal = CommandSpec.Literal(
    name = "trace",
    subcommands = listOf(CommandSpec.Argument(name = "mode", type = ArgumentType.Choice(listOf("on", "off")))),
    executor = { ctx ->
        val on = ctx.required<String>("mode") == "on"
        SkillEngineTrace.enabled = on
        ctx.reply(K_TRACE, mapOf("state" to if (on) "on" else "off"))
        CommandResult.Success
    },
).requirePermission(Permission("astera.skill.cast"))
