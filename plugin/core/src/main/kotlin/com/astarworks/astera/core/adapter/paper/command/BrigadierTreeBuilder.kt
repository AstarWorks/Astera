package com.astarworks.astera.core.adapter.paper.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandActor
import com.astarworks.astera.core.application.command.CommandAuthorizer
import com.astarworks.astera.core.application.command.CommandSpec
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider

/**
 * Translates the vendor-neutral `/astera` [CommandSpec] tree into a Brigadier
 * tree (ADR-0184). Generic in the Brigadier source type [S] so it can be
 * driven by a real dispatcher in tests without a running server; Paper plugs
 * in `CommandSourceStack` through [Env].
 *
 * Contract of the produced tree:
 *  - every literal `requires` the actor to be able to use it or something below
 *    it ([CommandAuthorizer.isVisible]) — nodes the sender may not use are
 *    absent from completion and from the client command tree;
 *  - arguments chain in declaration order; optional arguments make every node
 *    from the last mandatory one on executable, so `/astera items give p i`
 *    and `/astera items give p i 5` both run;
 *  - literal subcommands hang below the last argument of their parent;
 *  - the Brigadier tree is only a *skeleton*: execution and visibility
 *    re-resolve the node by [path] against the live registry ([Env.resolve]),
 *    so plugins that register late or disable never run stale executors.
 */
public class BrigadierTreeBuilder<S>(private val env: Env<S>) {

    public interface Env<S> {
        public val authorizer: CommandAuthorizer

        public fun actor(source: S): CommandActor

        /** The node currently registered at [path] (below the root), or `null` when gone. Empty path = root. */
        public fun resolve(path: List<String>): CommandSpec.Literal?

        /** Brigadier type for [type]; Paper maps [ArgumentType.PlayerSelector] to its native selector type. */
        public fun brigadierType(type: ArgumentType): com.mojang.brigadier.arguments.ArgumentType<*>

        /** Convert the raw Brigadier value into what [com.astarworks.astera.core.application.command.CommandContext.args] carries; `null` = invalid. */
        public fun convert(source: S, type: ArgumentType, raw: Any): Any?

        public fun onlinePlayerNames(source: S): List<String>

        /** Run [node] for [source] with the converted [args]. */
        public fun execute(path: List<String>, node: CommandSpec.Literal, source: S, args: Map<String, Any>)

        /** Tell the source the argument at hand was invalid. */
        public fun usage(source: S, path: List<String>, node: CommandSpec.Literal)
    }

    public fun build(root: CommandSpec.Literal): LiteralArgumentBuilder<S> = literal(root, emptyList())

    private fun literal(node: CommandSpec.Literal, path: List<String>): LiteralArgumentBuilder<S> {
        val builder = LiteralArgumentBuilder.literal<S>(node.name)
        builder.requires { source -> visible(source, path, node) }
        val args = node.arguments
        if (args.isEmpty()) {
            if (node.executor != null) builder.executes { ctx -> run(ctx, path, node) }
            for (child in node.literals) builder.then(literal(child, path + child.name))
            return builder
        }
        if (node.executor != null && node.requiredArgumentCount == 0) {
            builder.executes { ctx -> run(ctx, path, node) }
        }
        builder.then(argumentChain(node, path))
        return builder
    }

    private fun argumentChain(node: CommandSpec.Literal, path: List<String>): RequiredArgumentBuilder<S, *> {
        val args = node.arguments
        val builders = args.mapIndexed { index, arg -> argument(arg, args.take(index)) }
        for (i in builders.indices.reversed()) {
            val isLast = i == builders.lastIndex
            if (node.executor != null && i + 1 >= node.requiredArgumentCount) {
                builders[i].executes { ctx -> run(ctx, path, node) }
            }
            if (isLast) {
                for (child in node.literals) builders[i].then(literal(child, path + child.name))
            } else {
                builders[i].then(builders[i + 1])
            }
        }
        return builders.first()
    }

    private fun argument(arg: CommandSpec.Argument, prior: List<CommandSpec.Argument>): RequiredArgumentBuilder<S, *> {
        val builder = RequiredArgumentBuilder.argument<S, Any>(arg.name, env.brigadierType(arg.type) as com.mojang.brigadier.arguments.ArgumentType<Any>)
        val suggestions = suggestionsFor(arg, prior)
        if (suggestions != null) builder.suggests(suggestions)
        return builder
    }

    private fun suggestionsFor(arg: CommandSpec.Argument, prior: List<CommandSpec.Argument>): SuggestionProvider<S>? {
        val type = arg.type
        val hasBuiltin = type is ArgumentType.Choice || type is ArgumentType.PlayerName
        if (arg.completer == null && !hasBuiltin) return null
        return SuggestionProvider { ctx, sb ->
            val remaining = sb.remaining
            val actor = env.actor(ctx.source)
            val candidates = buildList {
                arg.completer?.let { c ->
                    addAll(c(CommandSpec.PartialInput(remaining, priorValues(ctx, prior), actor)))
                }
                when (type) {
                    is ArgumentType.Choice -> addAll(type.values)
                    is ArgumentType.PlayerName -> addAll(env.onlinePlayerNames(ctx.source))
                    else -> Unit
                }
            }
            candidates.distinct().filter { it.startsWith(remaining, ignoreCase = true) }.forEach { sb.suggest(it) }
            sb.buildFuture()
        }
    }

    private fun priorValues(ctx: CommandContext<S>, prior: List<CommandSpec.Argument>): Map<String, Any> {
        val values = LinkedHashMap<String, Any>()
        for (p in prior) {
            val raw = runCatching { ctx.getArgument(p.name, Any::class.java) }.getOrNull() ?: continue
            env.convert(ctx.source, p.type, raw)?.let { values[p.name] = it }
        }
        return values
    }

    // ---- execution ------------------------------------------------------------

    private fun visible(source: S, path: List<String>, snapshotNode: CommandSpec.Literal): Boolean {
        val live = env.resolve(path) ?: return false
        return env.authorizer.isVisible(env.actor(source), live)
    }

    private fun run(ctx: CommandContext<S>, path: List<String>, snapshotNode: CommandSpec.Literal): Int {
        val node = env.resolve(path)
        if (node == null || node.executor == null) {
            env.usage(ctx.source, path, snapshotNode)
            return 0
        }
        val args = LinkedHashMap<String, Any>()
        for (arg in node.arguments) {
            val raw = runCatching { ctx.getArgument(arg.name, Any::class.java) }.getOrNull() ?: continue
            val value = env.convert(ctx.source, arg.type, raw)
            if (value == null) {
                env.usage(ctx.source, path, node)
                return 0
            }
            args[arg.name] = value
        }
        env.execute(path, node, ctx.source, args)
        return com.mojang.brigadier.Command.SINGLE_SUCCESS
    }

    public companion object {
        /** Plain (non-Paper) Brigadier mapping shared by every [Env]. */
        public fun plainType(type: ArgumentType): com.mojang.brigadier.arguments.ArgumentType<*>? = when (type) {
            is ArgumentType.IntegerArg -> IntegerArgumentType.integer(type.min ?: Int.MIN_VALUE, type.max ?: Int.MAX_VALUE)
            is ArgumentType.DoubleArg -> DoubleArgumentType.doubleArg(type.min ?: -Double.MAX_VALUE, type.max ?: Double.MAX_VALUE)
            is ArgumentType.StringArg -> when (type.mode) {
                ArgumentType.StringArg.Mode.SingleWord -> TokenArgumentType
                ArgumentType.StringArg.Mode.Greedy -> StringArgumentType.greedyString()
            }
            is ArgumentType.ItemIdArg, is ArgumentType.PlayerName, is ArgumentType.Choice -> TokenArgumentType
            is ArgumentType.PlayerSelector -> null
        }
    }
}

/**
 * One whitespace-delimited token of any characters. Brigadier's own `word()`
 * rejects `:` and other characters that Astera ids use (`astera:forest-wolf`).
 */
internal object TokenArgumentType : com.mojang.brigadier.arguments.ArgumentType<String> {
    override fun parse(reader: com.mojang.brigadier.StringReader): String = read(reader)

    override fun getExamples(): Collection<String> = listOf("token", "astera:token")

    fun read(reader: com.mojang.brigadier.StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        return reader.string.substring(start, reader.cursor)
    }
}
