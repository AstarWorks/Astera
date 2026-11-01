package com.astarworks.astera.core.application.port.inbound

import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.services.ServiceRegistry

/**
 * Inbound port: the one place where every plugin contributes to the single
 * `/astera` command tree (ADR-0184, superseding the flat `/astera-*` literals
 * of ADR-0122).
 *
 * Core owns the Brigadier root `astera`. Feature plugins never register a
 * root: they hand over a subtree ([register]) that is mounted at
 * `/astera <parent...> <spec.name>`. Several plugins may contribute to the
 * same area (e.g. `admin`): literals with the same name are merged, a path
 * that two executors claim is a registration error.
 *
 * The platform adapter listens ([addListener]) and re-publishes the merged
 * [snapshot] to the host whenever the tree changes, so plugins that enable
 * after Core, and plugins that disable, are reflected without a restart.
 */
public interface IAsteraCommandRegistry {

    /**
     * Mount [spec] under `/astera` + [parent] (empty = directly under the
     * root). [owner] is the contributing plugin; it is what [unregisterOwner]
     * removes on disable. Throws [IllegalArgumentException] if the mounted tree
     * collides with an existing executor. [gen] is the owner's wiring generation (ADR-0311): while several
     * generations of one owner are registered only the newest is merged into the tree, so a rewire can mount the
     * new generation before the old one is removed.
     */
    public fun register(owner: String, gen: Int, parent: List<String>, spec: CommandSpec.Literal): ServiceRegistry.Registration

    /** Mount [spec] directly below the root (`/astera <spec.name>`). */
    public fun register(owner: String, gen: Int, spec: CommandSpec.Literal): ServiceRegistry.Registration =
        register(owner, gen, emptyList(), spec)

    /** Remove everything [owner] contributed. */
    public fun unregisterOwner(owner: String)

    /** The merged `astera` root as it stands now. */
    public fun snapshot(): CommandSpec.Literal

    /** Notified (on the registering thread) after every change to the tree. */
    public fun addListener(listener: () -> Unit): ServiceRegistry.Registration
}
