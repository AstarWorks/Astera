package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.domain.permission.Permission

/**
 * Vendor-neutral, declarative description of a command tree.
 *
 * Designed as the Astera replacement for the legacy `CommandAPI` plugin (see
 * silly-knuth §6.1 E, ADR-0015). The application layer defines _what_ commands
 * exist and _what to do_ for each branch. Plugins never register a root: they
 * mount subtrees under the single `/astera` root through
 * [com.astarworks.astera.core.application.port.inbound.IAsteraCommandRegistry]
 * (ADR-0184); `PaperCommandBridge` / `BrigadierTreeBuilder` translate the merged
 * tree into Paper's Brigadier, and `CommandTreeRunner` interprets it headless.
 *
 * Every executable [Literal] declares a [Literal.permission] (`astera.<area>.<action>`)
 * and a [Literal.access] default. Descriptions are not stored on the spec: the
 * node at path `queue join` is described by the flat i18n key
 * `astera.command.help.queue-join` ([CommandPaths.descriptionKey]).
 *
 * Two variants by design:
 *
 * - [Literal] — a fixed token in the command line (e.g. `astera`, `give`,
 *   `reload`). A literal may carry an [executor] (`/astera reload`) and / or
 *   nest further subcommands ([subcommands]). A literal with both an executor
 *   and subcommands runs the executor when no further token is present.
 * - [Argument] — a typed slot in the command line. Carries an
 *   [ArgumentType] and an optional [completer] for tab-completion. An
 *   argument NEVER carries an executor; the executor belongs to the literal
 *   that closes the branch.
 *
 * Trees are built bottom-up:
 *
 * ```
 * Literal(
 *     name = "astera",
 *     executor = { _ -> CommandResult.Success },
 *     subcommands = listOf(
 *         Literal(
 *             name = "give",
 *             subcommands = listOf(
 *                 Argument("target", ArgumentType.PlayerSelector),
 *                 Argument("weapon", ArgumentType.ItemIdArg),
 *             ),
 *             executor = { ctx ->
 *                 val target = ctx.required<PlayerId>("target")
 *                 val weapon = ctx.required<String>("weapon")
 *                 // ... call GiveWeaponUseCase ...
 *                 CommandResult.Success
 *             },
 *         ),
 *     ),
 * )
 * ```
 *
 * Conventions adopted by this DSL:
 *
 * - Arguments are listed as siblings under the literal that owns them;
 *   their order in [subcommands] is the order on the command line.
 *   This collapses Brigadier's deeply nested `.then(arg(...).then(arg(...)))`
 *   into a flat list at the spec level.
 * - A literal may declare zero or more leading [Argument]s followed by zero
 *   or more nested [Literal] subcommands. Mixed orderings (literal, argument,
 *   literal) are rejected by the adapter; this matches how CLI users actually
 *   read commands and keeps Brigadier translation straightforward.
 *
 * Suspend executors keep the door open for use cases that await
 * `IScheduler` work or use `await*` ports without blocking the server thread.
 * The adapter is responsible for launching them on the correct dispatcher.
 */
public sealed interface CommandSpec {

    /** Literal token. The backbone of the tree. */
    public data class Literal(
        public val name: String,
        public val executor: (suspend (CommandContext) -> CommandResult)? = null,
        public val subcommands: List<CommandSpec> = emptyList(),
        /**
         * Permission node (`astera.<area>.<action>`) required to run this node's
         * [executor]. Mandatory on every executable leaf (ADR-0184; asserted by
         * the whole-tree test). Enforced centrally by `CommandAuthorizer`, never
         * inside the executor.
         */
        public val permission: Permission? = null,
        /** Who holds [permission] by default. Fail-safe default: operators only. */
        public val access: CommandAccess = CommandAccess.OPERATOR,
    ) : CommandSpec {
        init {
            require(name.isNotBlank()) { "Literal name must not be blank" }
            require(name.matches(NAME_REGEX)) {
                "Literal name must be [a-z0-9_-]+: '$name'"
            }
            val args = subcommands.filterIsInstance<Argument>()
            val firstOptional = args.indexOfFirst { it.optional }
            if (firstOptional >= 0) {
                require(args.drop(firstOptional).all { it.optional }) {
                    "Literal '$name': a mandatory argument follows an optional one"
                }
            }
            // A literal with no executor and no subcommands is unreachable.
            require(executor != null || subcommands.isNotEmpty()) {
                "Literal '$name' has no executor and no subcommands (unreachable)"
            }
        }

        /** Returns the arguments declared directly under this literal, in order. */
        public val arguments: List<Argument>
            get() = subcommands.filterIsInstance<Argument>()

        /** Returns the nested literal subcommands. */
        public val literals: List<Literal>
            get() = subcommands.filterIsInstance<Literal>()

        /** Count of leading arguments that are mandatory (the rest are optional). */
        public val requiredArgumentCount: Int
            get() = arguments.indexOfFirst { it.optional }.let { if (it < 0) arguments.size else it }

        public companion object {
            private val NAME_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
        }
    }

    /** Typed argument slot. Holds no executor; bound by the enclosing literal. */
    public data class Argument(
        public val name: String,
        public val type: ArgumentType,
        public val completer: ((PartialInput) -> List<String>)? = null,
        /**
         * Optional arguments may be omitted (`[amount]`). Once one argument is
         * optional every later argument in the chain must be optional too. The
         * enclosing literal's executor runs with the arguments typed so far.
         */
        public val optional: Boolean = false,
    ) : CommandSpec {
        init {
            require(name.isNotBlank()) { "Argument name must not be blank" }
            require(name.matches(NAME_REGEX)) {
                "Argument name must be [a-z0-9_-]+: '$name'"
            }
        }

        public companion object {
            private val NAME_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
        }
    }

    /**
     * Snapshot of what the user has typed so far, handed to a completer.
     *
     * [remaining] is the partial token being completed (may be empty).
     * [priorArgs] holds already-parsed argument values keyed by name, so a
     * completer for `weapon` can react to which `target` was selected
     * earlier. Implementations should treat both as read-only.
     */
    public data class PartialInput(
        public val remaining: String,
        public val priorArgs: Map<String, Any> = emptyMap(),
        /** Who is completing; completers that list permission-gated things must filter by it. */
        public val actor: CommandActor = CommandActor(playerId = null, isOperator = true),
    )
}
