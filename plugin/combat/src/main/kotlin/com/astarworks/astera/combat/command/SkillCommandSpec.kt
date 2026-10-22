package com.astarworks.astera.combat.command

import com.astarworks.astera.combat.application.admin.AdminSkillCaster
import com.astarworks.astera.combat.application.admin.AdminSkillsPanel
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
 */
internal fun skillCommandSpec(caster: AdminSkillCaster, server: IMcServer): CommandSpec.Literal =
    CommandSpec.Literal(
        name = "skill",
        subcommands = listOf(listSubcommand(caster), castSubcommand(caster, server)),
    )

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
