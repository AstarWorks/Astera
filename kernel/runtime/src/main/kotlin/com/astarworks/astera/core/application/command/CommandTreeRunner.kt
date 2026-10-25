package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Host-neutral command line interpreter over the merged `/astera` tree
 * (ADR-0184): parse -> authorize -> execute, plus tab completion.
 *
 * It exists for the headless simulator and for tests; the Paper server uses
 * Brigadier for the same tree. Both resolve the node and call the same
 * [CommandInvoker], so what a test proves about permissions, executors and
 * replies holds on the server.
 *
 * @param root provider of the current merged root ([IAsteraCommandRegistry.snapshot]).
 * @param playerResolver name -> [PlayerId] for [ArgumentType.PlayerSelector].
 * @param onlinePlayerNames names suggested for player arguments.
 */
public class CommandTreeRunner(
    private val root: () -> CommandSpec.Literal,
    private val invoker: CommandInvoker,
    private val authorizer: CommandAuthorizer,
    private val playerResolver: (String) -> PlayerId? = { null },
    private val onlinePlayerNames: () -> List<String> = { emptyList() },
) {

    public sealed interface Outcome {
        public data class Executed(val result: CommandResult) : Outcome

        /** The line did not resolve to a runnable node; [usage] is the closest usage line. */
        public data class Usage(val usage: String) : Outcome
    }

    /** Run [line] (`queue join ranked`, optionally prefixed with `/astera`). */
    public suspend fun run(
        actor: CommandActor,
        line: String,
        reply: (MessageKey, Map<String, String>) -> Unit,
    ): Outcome {
        val tokens = tokenize(line)
        val tree = root()
        var node = tree
        var path = emptyList<String>()
        val args = LinkedHashMap<String, Any>()
        var pos = 0
        while (true) {
            var bound = 0
            for (arg in node.arguments) {
                if (pos >= tokens.size) break
                val raw = if (isGreedy(arg)) tokens.drop(pos).joinToString(" ") else tokens[pos]
                val value = parse(arg.type, raw)
                if (value == null) {
                    return usage(reply, path, node)
                }
                if (value is Missing) {
                    reply(MessageKey("astera.command.player-not-found"), mapOf("player" to raw))
                    return Outcome.Executed(CommandResult.Failure(MessageKey("astera.command.player-not-found")))
                }
                args[arg.name] = value
                pos += if (isGreedy(arg)) tokens.size - pos else 1
                bound++
            }
            if (pos >= tokens.size) {
                if (node.executor == null || bound < node.requiredArgumentCount) return usage(reply, path, node)
                return Outcome.Executed(invoker.invoke(node, actor, args, reply))
            }
            if (bound < node.arguments.size) return usage(reply, path, node)
            val child = node.literals.firstOrNull { it.name == tokens[pos] && authorizer.isVisible(actor, it) }
                ?: return usage(reply, path, node)
            node = child
            path = path + child.name
            pos++
        }
    }

    /** Tab completions for [line]; a trailing space means "complete the next token". */
    public fun suggest(actor: CommandActor, line: String): List<String> {
        val tokens = tokenize(line)
        val partial = if (line.isEmpty() || line.last().isWhitespace()) "" else tokens.lastOrNull().orEmpty()
        val complete = if (partial.isEmpty()) tokens else tokens.dropLast(1)
        var node = root()
        val args = LinkedHashMap<String, Any>()
        var pos = 0
        while (true) {
            var bound = 0
            for (arg in node.arguments) {
                if (pos >= complete.size) break
                val value = parse(arg.type, complete[pos]) ?: return emptyList()
                if (value is Missing) return emptyList()
                args[arg.name] = value
                pos++
                bound++
            }
            if (pos >= complete.size) {
                val candidates = if (bound < node.arguments.size) {
                    argumentCandidates(actor, node.arguments[bound], partial, args)
                } else {
                    node.literals.filter { authorizer.isVisible(actor, it) }.map { it.name }
                }
                return candidates.filter { it.startsWith(partial, ignoreCase = true) }.distinct()
            }
            if (bound < node.arguments.size) return emptyList()
            node = node.literals.firstOrNull { it.name == complete[pos] && authorizer.isVisible(actor, it) }
                ?: return emptyList()
            pos++
        }
    }

    private fun usage(
        reply: (MessageKey, Map<String, String>) -> Unit,
        path: List<String>,
        node: CommandSpec.Literal,
    ): Outcome.Usage {
        val usage = CommandPaths.usage(path, node)
        reply(CommandInvoker.USAGE, mapOf("usage" to usage))
        return Outcome.Usage(usage)
    }

    private fun argumentCandidates(actor: CommandActor, arg: CommandSpec.Argument, partial: String, prior: Map<String, Any>): List<String> {
        val own = arg.completer?.invoke(CommandSpec.PartialInput(partial, prior, actor)).orEmpty()
        val builtin = when (val t = arg.type) {
            is ArgumentType.Choice -> t.values
            is ArgumentType.PlayerName, is ArgumentType.PlayerSelector -> onlinePlayerNames()
            else -> emptyList()
        }
        return own + builtin
    }

    private fun isGreedy(arg: CommandSpec.Argument): Boolean =
        (arg.type as? ArgumentType.StringArg)?.mode == ArgumentType.StringArg.Mode.Greedy

    /** Marker: syntactically valid token that names no known player. */
    private object Missing

    /** Parsed value, [Missing], or `null` when [raw] is not valid for [type]. */
    private fun parse(type: ArgumentType, raw: String): Any? = when (type) {
        is ArgumentType.IntegerArg -> raw.toIntOrNull()?.takeIf { (type.min == null || it >= type.min) && (type.max == null || it <= type.max) }
        is ArgumentType.DoubleArg -> raw.toDoubleOrNull()?.takeIf { (type.min == null || it >= type.min) && (type.max == null || it <= type.max) }
        is ArgumentType.StringArg, ArgumentType.ItemIdArg, ArgumentType.PlayerName -> raw
        is ArgumentType.Choice -> type.values.firstOrNull { it.equals(raw, ignoreCase = true) }
        is ArgumentType.PlayerSelector -> playerResolver(raw) ?: Missing
    }

    private fun tokenize(line: String): List<String> {
        var tokens = line.trim().removePrefix("/").trim().split(WHITESPACE).filter { it.isNotEmpty() }
        tokens = if (tokens.firstOrNull().equals(AsteraCommandRegistry.ROOT_NAME, ignoreCase = true)) {
            tokens.drop(1)
        } else {
            CommandShortcuts.expand(tokens) // `/play norma` == `/astera queue join norma` (ADR-0234)
        }
        return tokens
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
