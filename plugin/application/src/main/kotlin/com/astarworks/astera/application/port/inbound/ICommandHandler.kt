package com.astarworks.astera.application.port.inbound

import com.astarworks.astera.application.command.CommandSpec

/**
 * Inbound port: register declarative command trees with the platform.
 *
 * "Inbound" because the platform calls _into_ the application (via the spec's
 * executors) when a user types a command. The application orchestrates use
 * cases; the platform owns the parsing and dispatch.
 *
 * Implementations live in the platform adapter (Paper Brigadier today, Folia /
 * Velocity / proxy console tomorrow). Per ADR-0015 Astera does NOT depend on
 * any external command plugin (CommandAPI / ACF / Brigadier-CommandUtil-shim),
 * so [CommandSpec] is the entire vocabulary the application layer ever
 * speaks about commands.
 *
 * Registration is one-shot at plugin enable; there is no "unregister single
 * spec" operation in Phase 1. Re-enabling the plugin re-registers everything.
 * If a need for runtime hot-reload appears (e.g. content-driven commands),
 * add it as a new method here rather than mutating the spec tree from the
 * outside.
 */
public interface ICommandHandler {

    /**
     * Register [spec] (which MUST be a [CommandSpec.Literal] — the root of a
     * command tree) with the platform. After this returns, typing the
     * literal's [CommandSpec.Literal.name] in chat / console invokes the
     * matching executor.
     *
     * Implementations validate the tree shape (e.g. greedy strings must be
     * the trailing argument in their branch) and throw
     * [IllegalArgumentException] on violations. Validation happens at
     * registration, not at command-typing time, so authoring bugs surface
     * at plugin enable.
     */
    public fun register(spec: CommandSpec.Literal)
}
