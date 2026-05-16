package com.astarworks.astera.core.adapter.paper.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext as BrigadierContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Paper Brigadier-backed [ICommandHandler].
 *
 * This is the **only** module that knows how Astera's vendor-neutral
 * [CommandSpec] DSL maps to a concrete server's command parser. Everything
 * above it stays free of `com.mojang.*` / `io.papermc.*` imports (enforced by
 * Konsist's `application layer must not import Bukkit family or persistence
 * drivers` rule).
 *
 * Registration flow:
 *
 * 1. Plugin enable wires up `PaperCommandHandler`, then each module calls
 *    [register] with its root [CommandSpec.Literal]. Specs are buffered.
 * 2. The handler subscribes once to Paper's
 *    [LifecycleEvents.COMMANDS] (re-fires on `/reload`), and at each fire it
 *    rebuilds every buffered spec into a Brigadier `LiteralCommandNode` and
 *    registers it via [Commands.register].
 *
 * Per ADR-0015 Astera does NOT depend on any external command plugin; this
 * adapter only consumes Paper's stock Brigadier API and Brigadier core
 * (transitively shipped by paper-api).
 *
 * Executor dispatch:
 *
 * Brigadier requires the command lambda to return synchronously. We pre-stage
 * a [CoroutineScope] (`SupervisorJob` so one failing executor does not poison
 * others), launch the suspending executor on
 * [IScheduler.mainDispatcher] so use cases run on the server thread by
 * default, and report 1 back to Brigadier immediately. Long-running work the
 * use case wants off-thread should swap dispatcher via the `IScheduler`
 * `withContext(...)` ports — this handler stays opinion-light about how each
 * use case schedules itself.
 */
public class PaperCommandHandler(
    private val plugin: JavaPlugin,
    private val scheduler: IScheduler,
) : ICommandHandler {

    private val buffered = CopyOnWriteArrayList<CommandSpec.Literal>()
    private val scope = CoroutineScope(SupervisorJob() + scheduler.mainDispatcher)

    /**
     * Whether the Paper lifecycle subscription has been wired. Lifecycle
     * managers fire COMMANDS on enable AND on `/reload`, so we keep the spec
     * list alive across invocations.
     */
    @Volatile
    private var subscribed: Boolean = false

    override fun register(spec: CommandSpec.Literal) {
        validate(spec)
        buffered.add(spec)
        ensureSubscribed()
    }

    private fun ensureSubscribed() {
        if (subscribed) return
        synchronized(this) {
            if (subscribed) return
            plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
                val commands = event.registrar()
                for (spec in buffered) {
                    val node = build(spec).build()
                    commands.register(plugin.pluginMeta, node, spec.description?.value, emptyList())
                }
            }
            subscribed = true
        }
    }

    // ----- Build: CommandSpec → Brigadier ----------------------------------

    private fun build(spec: CommandSpec.Literal): com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> {
        val literal = Commands.literal(spec.name)

        // Attach nested children. Per the spec contract: leading [Argument]s
        // form a chain on this literal, then nested [Literal]s branch from
        // the deepest argument (or directly from this literal if there are
        // no arguments).
        val arguments = spec.arguments
        val literals = spec.literals

        if (arguments.isEmpty()) {
            spec.executor?.let { exec -> literal.executes { brigCtx -> dispatch(exec, brigCtx, emptyList()) } }
            for (sub in literals) literal.then(build(sub))
            return literal
        }

        // Chain arguments: arg[0].then(arg[1].then(... arg[last].then(children)))
        // We build from the tail back so each layer's `.then(...)` wires to the next.
        val tailBuilder = buildArgumentChain(arguments, literals, spec.executor)
        literal.then(tailBuilder)
        return literal
    }

    /**
     * Build a chain `arg[0] -> arg[1] -> ... -> arg[n]` where the deepest
     * argument carries the [executor] (so Brigadier dispatches once all
     * tokens are bound) and the children literals.
     */
    private fun buildArgumentChain(
        arguments: List<CommandSpec.Argument>,
        literals: List<CommandSpec.Literal>,
        executor: (suspend (CommandContext) -> CommandResult)?,
    ): RequiredArgumentBuilder<CommandSourceStack, *> {
        require(arguments.isNotEmpty()) { "buildArgumentChain called with no arguments" }

        // Build in reverse so each parent .then()s its child.
        val builders = arguments.map { arg -> brigadierArgument(arg) }
        for (i in builders.indices.reversed()) {
            val isLast = i == builders.lastIndex
            val arg = arguments[i]

            if (isLast) {
                if (executor != null) {
                    val names = arguments.map { it.name }
                    builders[i].executes { brigCtx -> dispatch(executor, brigCtx, names) }
                }
                for (sub in literals) builders[i].then(build(sub))
            } else {
                builders[i].then(builders[i + 1])
            }

            attachCompleter(builders[i], arg)
        }
        return builders.first()
    }

    /** Translate one [CommandSpec.Argument] into a Brigadier required-argument builder. */
    private fun brigadierArgument(arg: CommandSpec.Argument): RequiredArgumentBuilder<CommandSourceStack, *> {
        return when (val t = arg.type) {
            is ArgumentType.PlayerSelector ->
                Commands.argument(arg.name, ArgumentTypes.player())

            is ArgumentType.IntegerArg -> {
                val min = t.min ?: Int.MIN_VALUE
                val max = t.max ?: Int.MAX_VALUE
                Commands.argument(arg.name, IntegerArgumentType.integer(min, max))
            }

            is ArgumentType.StringArg -> when (t.mode) {
                ArgumentType.StringArg.Mode.SingleWord ->
                    Commands.argument(arg.name, StringArgumentType.word())
                ArgumentType.StringArg.Mode.Greedy ->
                    Commands.argument(arg.name, StringArgumentType.greedyString())
            }

            is ArgumentType.ItemIdArg ->
                // Astera content ids are free-form tokens; tab-completion is
                // attached separately via [attachCompleter] if the spec
                // supplies a completer (typically `IItemRegistry.list()`).
                Commands.argument(arg.name, StringArgumentType.word())
        }
    }

    private fun attachCompleter(builder: RequiredArgumentBuilder<CommandSourceStack, *>, arg: CommandSpec.Argument) {
        val completer = arg.completer ?: return
        builder.suggests { brigCtx, sb: SuggestionsBuilder ->
            val remaining = sb.remaining
            val prior = collectPriorArgs(brigCtx, arg.name)
            val suggestions = completer(CommandSpec.PartialInput(remaining, prior))
            for (s in suggestions) {
                if (s.startsWith(remaining, ignoreCase = true)) sb.suggest(s)
            }
            sb.buildFuture()
        }
    }

    /**
     * Pull already-parsed arguments out of the Brigadier context to expose
     * via [CommandSpec.PartialInput.priorArgs]. We intentionally swallow
     * `IllegalArgumentException` from `getArgument` (Brigadier throws if the
     * name is unbound on this branch) — incomplete prior context is fine for
     * a completer.
     */
    private fun collectPriorArgs(
        brigCtx: BrigadierContext<CommandSourceStack>,
        currentArgName: String,
        @Suppress("UNUSED_PARAMETER") names: List<String> = emptyList(),
    ): Map<String, Any> {
        // Brigadier doesn't expose the bound argument names directly here; in
        // Phase 1 we hand back an empty map and let completers stay
        // context-free. A richer "prior args" surface is a Phase 2 mid
        // follow-up once a real consumer asks for it.
        return emptyMap()
    }

    // ----- Dispatch: Brigadier → application executor ----------------------

    /**
     * Brigadier `Command<CommandSourceStack>` body. Resolves all bound
     * arguments into [CommandContext], launches the suspend executor on the
     * main dispatcher, and reports a synchronous 1 (Brigadier "success") so
     * Brigadier itself does not retry. Errors / failures are surfaced via
     * sender messages, not via Brigadier exceptions.
     */
    private fun dispatch(
        executor: suspend (CommandContext) -> CommandResult,
        brigCtx: BrigadierContext<CommandSourceStack>,
        argNames: List<String>,
    ): Int {
        val source = brigCtx.source
        val senderEntity = source.executor ?: source.sender
        val playerId = (senderEntity as? Player)?.let { PlayerId(it.uniqueId) }

        val resolved = HashMap<String, Any>(argNames.size)
        for (name in argNames) {
            val v = runCatching { brigCtx.getArgument(name, Any::class.java) }.getOrNull()
                ?: continue
            resolved[name] = unwrapArgValue(v, brigCtx)
        }

        val appCtx = CommandContext(sender = playerId, args = resolved, scheduler = scheduler)
        scope.launch {
            val result = runCatching { executor(appCtx) }
                .getOrElse { t ->
                    plugin.logger.warning("Astera command executor failed: ${t.message}")
                    CommandResult.Failure(MessageKey("astera.command.internal_error"))
                }
            applyResult(result, source)
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS
    }

    /**
     * Convert Paper's resolver-style argument values into the simple domain
     * values [CommandContext] callers expect.
     *
     * - `PlayerSelectorArgumentResolver` → `PlayerId` of the first resolved
     *   player. Empty selections become `null` (executor receives no entry).
     * - Everything else passes through as-is (Int / String are already the
     *   types declared by [ArgumentType]).
     */
    private fun unwrapArgValue(raw: Any, brigCtx: BrigadierContext<CommandSourceStack>): Any {
        return when (raw) {
            is PlayerSelectorArgumentResolver -> {
                val players = runCatching { raw.resolve(brigCtx.source) }.getOrNull().orEmpty()
                val first = players.firstOrNull()
                first?.let { PlayerId(it.uniqueId) } ?: SENTINEL_EMPTY
            }
            else -> raw
        }
    }

    private fun applyResult(result: CommandResult, source: CommandSourceStack) {
        when (result) {
            is CommandResult.Success -> Unit
            is CommandResult.Failure -> {
                // Phase 1: emit a minimal, non-rendered notice to the sender.
                // Full i18n rendering happens via IMessageRenderer; we keep
                // this adapter free of an IMessageRenderer dependency for now
                // so it stays trivially constructible. Executors that want
                // localized output should render themselves and call
                // `IPlayerGateway.sendMessage(...)` before returning Success.
                source.sender.sendMessage("[${result.messageKey.value}]")
            }
        }
    }

    // ----- Validation ------------------------------------------------------

    private fun validate(spec: CommandSpec) {
        when (spec) {
            is CommandSpec.Literal -> {
                val args = spec.arguments
                // Greedy strings must be the trailing argument (Brigadier rule).
                args.forEachIndexed { index, arg ->
                    val type = arg.type
                    if (type is ArgumentType.StringArg && type.mode == ArgumentType.StringArg.Mode.Greedy) {
                        require(index == args.lastIndex) {
                            "Argument '${arg.name}' on literal '${spec.name}': greedy strings must be the trailing argument"
                        }
                    }
                }
                spec.literals.forEach { validate(it) }
            }
            is CommandSpec.Argument -> Unit
        }
    }

    private companion object {
        /**
         * Sentinel for an empty selector resolution. Stored in the args map
         * lets callers distinguish "selector was provided but matched no one"
         * from "argument was never typed". Use cases typically null-check
         * via `ctx.optional<PlayerId>("target")` and treat sentinel as
         * "no target found".
         */
        private val SENTINEL_EMPTY: Any = Any()
    }
}
