package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.inbound.IAsteraCommandRegistry
import com.astarworks.astera.core.application.services.ServiceRegistry

/**
 * A plugin's handle on the `/astera` tree (ADR-0184): mounts subtrees under
 * [owner] and records each registration in [sink] (the wiring context's
 * `registrations`), so closing the context on plugin disable removes exactly
 * what this plugin contributed.
 */
public class CommandMount(
    private val registry: IAsteraCommandRegistry,
    public val owner: String,
    private val sink: MutableList<ServiceRegistry.Registration>,
) {

    /** Mount [spec] at `/astera <parent...> <spec.name>`. */
    public fun register(parent: List<String>, spec: CommandSpec.Literal) {
        sink += registry.register(owner, parent, spec)
    }

    /** Mount [spec] at `/astera <area> <spec.name>`. */
    public fun register(area: String, spec: CommandSpec.Literal) {
        register(listOf(area), spec)
    }

    /** Mount [spec] at `/astera <spec.name>`. */
    public fun register(spec: CommandSpec.Literal) {
        register(emptyList(), spec)
    }
}
