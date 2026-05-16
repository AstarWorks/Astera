package com.astarworks.astera.application.command

import com.astarworks.astera.domain.model.i18n.MessageKey

/**
 * Vendor-neutral, declarative description of a command tree.
 *
 * Designed as the Astera replacement for the legacy `CommandAPI` plugin (see
 * silly-knuth §6.1 E, ADR-0015). The application layer defines _what_ commands
 * exist and _what to do_ for each branch; the adapter
 * (`PaperCommandHandler` in `adapter/minecraft-impl-paper`) translates the
 * tree into the platform's native parser (Paper's Brigadier integration).
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
 *     description = MessageKey("astera.command.root"),
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
        public val description: MessageKey? = null,
        public val executor: (suspend (CommandContext) -> CommandResult)? = null,
        public val subcommands: List<CommandSpec> = emptyList(),
    ) : CommandSpec {
        init {
            require(name.isNotBlank()) { "Literal name must not be blank" }
            require(name.matches(NAME_REGEX)) {
                "Literal name must be [a-z0-9_-]+: '$name'"
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

        public companion object {
            private val NAME_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
        }
    }

    /** Typed argument slot. Holds no executor; bound by the enclosing literal. */
    public data class Argument(
        public val name: String,
        public val type: ArgumentType,
        public val completer: ((PartialInput) -> List<String>)? = null,
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
    )
}
