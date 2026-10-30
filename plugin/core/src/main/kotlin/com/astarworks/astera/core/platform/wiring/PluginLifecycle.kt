package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result

/** Why a lifecycle step failed; becomes the cause text of a `RewireError`. */
public data class LifecycleFailure(val message: String)

/**
 * One rewirable plugin (ADR-0311). The coordinator drives it through two phases per generation:
 *
 * - [wire] is pure registration: services, event subscriptions, GUI intents, reloadables, commands, Bukkit
 *   listeners, all through the [WiringContext] funnel. It starts no task, spawns nothing and opens no connection, so
 *   a second generation can be built next to the running one and thrown away again.
 * - [bind] is every side effect: scheduler tasks, NPC / hologram spawns, background loops, external connections.
 *   [unbind] stops them again. Only one generation is bound at a time.
 *
 * Teardown of the registrations themselves (the "unwire") is tag-wide and done by [WiringContext.close]; a
 * lifecycle never unregisters by hand. In-memory state that must survive is resolved with
 * [WiringContext.stateful] inside [wire].
 *
 * [W] is whatever [wire] produces and [bind] / [unbind] need (use cases, hooks).
 */
public interface PluginLifecycle<W : Any> {

    /** The plugin.yml name. */
    public val owner: WiringOwner

    /** Plugins whose services this one resolves in [wire]; they are rewired together with their dependency. */
    public val dependsOn: List<WiringOwner>

    public fun wire(ctx: WiringContext): Result<W, LifecycleFailure>

    public fun bind(ctx: WiringContext, wired: W): Result<Unit, LifecycleFailure>

    public fun unbind(ctx: WiringContext, wired: W)

    /**
     * Re-establishes the external connection this plugin owns (`astera admin rewire <plugin> --reconnect`), run
     * before the new generation is staged. Must leave the old connection serving on failure. Plugins without a
     * connection keep the default refusal.
     */
    public fun reconnect(): Result<Unit, LifecycleFailure> =
        Result.Failure(LifecycleFailure("$owner owns no connection to re-establish"))
}
