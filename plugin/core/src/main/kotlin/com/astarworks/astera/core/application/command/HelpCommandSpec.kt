package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.inbound.IAsteraCommandRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission

/**
 * `/astera` and `/astera help [area]` (ADR-0184).
 *
 * Both walk the live merged tree ([IAsteraCommandRegistry.snapshot]) and list
 * only what the sender may use, so the help can never drift from the commands:
 *
 *  - `/astera`, `/astera help`: one line per visible area;
 *  - `/astera help <area>`: every runnable command of that area with its
 *    usage and description (`astera.command.help.<path>`).
 */
public class HelpCommands(
    private val registry: IAsteraCommandRegistry,
    private val authorizer: CommandAuthorizer,
    private val messages: IMessageRenderer,
) {

    /**
     * Executor for the bare `/astera` (ADR-0234): a player gets the main menu (the GUI is the
     * discoverable front door); the console, or a player whose menu cannot open, gets the help overview.
     */
    public val overview: suspend (CommandContext) -> CommandResult = lambda@{ ctx ->
        val menu = registry.snapshot().literals.firstOrNull { it.name == "menu" }
        val open = menu?.executor
        if (ctx.sender != null && menu != null && open != null && authorizer.canUse(ctx.actor, menu)) {
            if (open(ctx) is CommandResult.Success) return@lambda CommandResult.Success
        }
        show(ctx, area = null)
    }

    /** The `help [area]` literal. */
    public fun literal(): CommandSpec.Literal = CommandSpec.Literal(
        name = "help",
        permission = Permission("astera.help.use"),
        access = CommandAccess.EVERYONE,
        subcommands = listOf(
            CommandSpec.Argument(
                name = "area",
                type = ArgumentType.StringArg(),
                optional = true,
                completer = { input -> visibleAreas(input.actor) },
            ),
        ),
        executor = { ctx -> show(ctx, ctx.optional<String>("area")) },
    )

    private fun visibleAreas(actor: CommandActor): List<String> =
        registry.snapshot().literals.filter { authorizer.isVisible(actor, it) }.map { it.name }.sorted()

    private fun show(ctx: CommandContext, area: String?): CommandResult {
        val root = registry.snapshot()
        val actor = ctx.actor
        val visibleAreas = root.literals.filter { authorizer.isVisible(actor, it) }.sortedBy { it.name }

        if (area == null) {
            ctx.reply(MessageKey("astera.command.help.header"))
            for (node in visibleAreas) {
                ctx.reply(
                    MessageKey("astera.command.help.area-line"),
                    mapOf("area" to node.name, "description" to describe(ctx, listOf(node.name))),
                )
            }
            ctx.reply(MessageKey("astera.command.help.footer"))
            return CommandResult.Success
        }

        val node = visibleAreas.firstOrNull { it.name.equals(area, ignoreCase = true) }
            ?: return CommandResult.Failure(MessageKey("astera.command.help.unknown-area"), mapOf("area" to area))
        ctx.reply(MessageKey("astera.command.help.area-header"), mapOf("area" to node.name))
        val rows = mutableListOf<Pair<String, String>>()
        fun collect(path: List<String>, n: CommandSpec.Literal) {
            if (n.executor != null && authorizer.canUse(actor, n)) {
                rows += CommandPaths.usage(path, n) to describe(ctx, path)
            }
            n.literals.filter { authorizer.isVisible(actor, it) }.sortedBy { it.name }
                .forEach { collect(path + it.name, it) }
        }
        collect(listOf(node.name), node)
        for ((usage, description) in rows) {
            ctx.reply(MessageKey("astera.command.help.line"), mapOf("usage" to usage, "description" to description))
        }
        return CommandResult.Success
    }

    private fun describe(ctx: CommandContext, path: List<String>): String =
        messages.render(ctx.sender, CommandPaths.descriptionKey(path))
}
