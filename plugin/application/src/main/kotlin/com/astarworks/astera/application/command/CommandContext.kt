package com.astarworks.astera.application.command

import com.astarworks.astera.application.port.outbound.IScheduler
import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Vendor-neutral view of a command invocation, handed to a
 * [CommandSpec.Literal.executor].
 *
 * The executor receives only domain-level values:
 *
 * - [sender] — the invoking player, or `null` when invoked from the console
 *   or a command block. Use cases that strictly require a player should
 *   return [CommandResult.Failure] with an appropriate `astera.command.*`
 *   message key when this is `null`.
 * - [args] — argument values keyed by the [CommandSpec.Argument.name] declared
 *   in the spec. Runtime types are dictated by [ArgumentType] (see kdoc on
 *   each variant). Always present for arguments that the parser bound;
 *   optional / not-yet-typed branches simply omit the entry.
 * - [scheduler] — handed in so executors can defer follow-up work without
 *   each adapter sneaking a second port reference into the lambda's closure.
 *
 * `args` is a plain `Map<String, Any>` rather than a typed envelope on
 * purpose: the spec already declares the type per argument, and forcing every
 * command branch to define its own DTO class would multiply boilerplate.
 * Use the [arg] helpers below for safer reads at the call site.
 */
public data class CommandContext(
    public val sender: PlayerId?,
    public val args: Map<String, Any>,
    public val scheduler: IScheduler,
) {

    /** True when the invoker is the server console (no associated player). */
    public val isConsole: Boolean get() = sender == null

    /**
     * Read [name] as [T]. Throws [IllegalStateException] if missing or wrong
     * type — both indicate a spec-vs-executor mismatch, which is a bug.
     */
    public inline fun <reified T : Any> required(name: String): T {
        val v = args[name] ?: error("Missing required argument '$name'")
        return v as? T ?: error(
            "Argument '$name' bound as ${v::class.simpleName}, expected ${T::class.simpleName}",
        )
    }

    /** Read [name] as [T] if present and of the right type, else `null`. */
    public inline fun <reified T : Any> optional(name: String): T? = args[name] as? T
}
