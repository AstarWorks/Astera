package com.astarworks.astera.combat.adapter.paper.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * `/astera skill expand <skill-id> [--json]` and `/astera skill expand all` (ADR-0303 11.3, C1).
 *
 * Prints the compiled [ExpansionTable] of a loaded skill through [ExpansionDump] (text, or JSON with `src` per row)
 * and writes both forms to [outDir] (`<skill-id>.txt` / `<skill-id>.json`); `all` writes every loaded skill.
 * [outDir] is the `expand_dir` key of `content/engine/skill-engine.yaml` (`EngineConfig.expandDir`); there is no fallback path.
 * Read-only, so it reuses the `astera.skill.list` permission. The chat copy replaces `<` / `>` with `‹` / `›`
 * because `<L:...>` values would be read as MiniMessage tags; the files keep the exact dump.
 */
public fun skillExpandSubcommand(
    ids: () -> Collection<String>,
    tableOf: (String) -> ExpansionTable?,
    outDir: Path,
): CommandSpec.Literal = CommandSpec.Literal(
    name = "expand",
    subcommands = listOf(
        CommandSpec.Argument(
            name = "target",
            type = ArgumentType.StringArg(),
            completer = { _ -> ids().sorted() + ALL },
        ),
        CommandSpec.Argument(name = "flag", type = ArgumentType.Choice(listOf(JSON)), optional = true),
    ),
    executor = { ctx -> runExpand(ctx, ids, tableOf, outDir) },
).requirePermission(Permission("astera.skill.list"))

private const val ALL = "all"
private const val JSON = "--json"

private val K_LINE = MessageKey("astera.skill.expand.line")
private val K_WRITTEN = MessageKey("astera.skill.expand.written")
private val K_WRITE_FAILED = MessageKey("astera.skill.expand.write-failed")
private val K_UNKNOWN = MessageKey("astera.admin.panel.skills.err.unknown")

private fun runExpand(ctx: CommandContext, ids: () -> Collection<String>, tableOf: (String) -> ExpansionTable?, outDir: Path): CommandResult {
    val target = ctx.required<String>("target")
    val asJson = ctx.optional<String>("flag") == JSON
    return try {
        if (target == ALL) {
            val tables = ids().sorted().mapNotNull(tableOf)
            tables.forEach { write(outDir, it) }
            ctx.reply(K_WRITTEN, mapOf("path" to outDir.toAbsolutePath().toString(), "count" to tables.size.toString()))
            return CommandResult.Success
        }
        val table = tableOf(target) ?: return CommandResult.Failure(K_UNKNOWN, mapOf("skill" to target))
        write(outDir, table)
        if (asJson) {
            ctx.reply(K_WRITTEN, mapOf("path" to outDir.resolve("${table.skillId}.json").toAbsolutePath().toString(), "count" to "1"))
        } else {
            ExpansionDump.render(table).lineSequence().filter { it.isNotEmpty() }.forEach { ctx.reply(K_LINE, mapOf("line" to chatSafe(it))) }
        }
        CommandResult.Success
    } catch (e: IOException) {
        CommandResult.Failure(K_WRITE_FAILED, mapOf("path" to outDir.toString()))
    }
}

private fun write(outDir: Path, table: ExpansionTable) {
    outDir.createDirectories()
    Files.writeString(outDir.resolve("${table.skillId}.txt"), ExpansionDump.render(table))
    Files.writeString(outDir.resolve("${table.skillId}.json"), ExpansionDump.json(table))
}

private fun chatSafe(line: String): String = line.replace('<', '‹').replace('>', '›')
