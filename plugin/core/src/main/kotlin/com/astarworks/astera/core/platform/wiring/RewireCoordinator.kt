package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.wiring.IRewireService
import com.astarworks.astera.core.application.wiring.RewireChange
import com.astarworks.astera.core.application.wiring.RewireError
import com.astarworks.astera.core.application.wiring.RewireReport
import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result

/**
 * Drives [PluginLifecycle]s through boot, rewire and shutdown (ADR-0311 P1). Single-threaded: called from the main
 * thread (the Paper host) or the simulator's tick thread.
 *
 * A rewire of plugin T:
 * 1. plan: T plus every registered, running plugin that transitively depends on T, in registration order;
 * 2. stage: wire generation N+1 of each (old generation still running; nothing is bound). A failure drops the staged
 *    contexts and leaves generation N untouched;
 * 3. commit: unbind generation N (reverse order), bind generation N+1 (forward order). A failure unbinds what was
 *    bound, drops the new generation and re-binds the old one (`BindFailed(rolledBack)`);
 * 4. retire: close generation N's contexts, which unregisters everything stamped with its tags;
 * 5. report before/after counts per plugin; a count that fell to 0 is a warning.
 *
 * Steps 2-4 run inside the registry's rewire window (ADR-0311 P3), so a plugin that overlays services at a higher
 * priority (Persistence) is not reported as a late override: its consumers are in the same plan and are rebuilt.
 */
public class RewireCoordinator(private val base: WiringContext) : IRewireService {

    private class Slot(val lifecycle: PluginLifecycle<Any>) {
        var ctx: WiringContext? = null
        var wired: Any? = null
        var gen: Int = -1
        val up: Boolean get() = ctx != null
    }

    private val slots = linkedMapOf<WiringOwner, Slot>()
    private var busy = false

    /** Plugin ids that exist but have no lifecycle (yet); a rewire request for them is RESTART_REQUIRED. */
    private val knownPlugins = listOf(
        "Astera-Core", "Astera-Chat", "Astera-Items", "Astera-Combat", "Astera-Match",
        "Astera-Stats", "Astera-Economy", "Astera-Persistence",
    )

    @Suppress("UNCHECKED_CAST")
    public fun <W : Any> register(lifecycle: PluginLifecycle<W>) {
        slots[lifecycle.owner] = Slot(lifecycle as PluginLifecycle<Any>)
    }

    /** Boots generation 0 of [owner]: wire, then bind. Failures leave the plugin down. */
    public fun up(owner: WiringOwner): Result<Unit, RewireError> {
        val slot = slots[owner] ?: return Result.Failure(unknown(owner.id))
        check(!slot.up) { "$owner is already up" }
        val ctx = base.forOwner(owner, 0)
        ctx.hold()
        val wired = when (val r = slot.lifecycle.wire(ctx)) {
            is Result.Success -> r.value
            is Result.Failure -> {
                ctx.close()
                return Result.Failure(RewireError.WireFailed(owner.id, r.error.message))
            }
        }
        ctx.activate()
        when (val r = slot.lifecycle.bind(ctx, wired)) {
            is Result.Success -> Unit
            is Result.Failure -> {
                runCatching { slot.lifecycle.unbind(ctx, wired) }
                ctx.close()
                return Result.Failure(RewireError.BindFailed(owner.id, r.error.message, rolledBack = true))
            }
        }
        slot.ctx = ctx
        slot.wired = wired
        slot.gen = 0
        return Result.Success(Unit)
    }

    /**
     * Registers [lifecycle] and takes over generation 0 that was already wired by the plugin itself before Core's
     * wiring existed (Persistence registers its overlays in `onLoad`, ADR-0195, so no consumer can capture a
     * default). The registrations stamped `(owner, 0)` are adopted as they are; this only binds.
     */
    public fun <W : Any> adopt(lifecycle: PluginLifecycle<W>, wired: W): Result<Unit, RewireError> {
        register(lifecycle)
        val owner = lifecycle.owner
        val slot = slots.getValue(owner)
        check(!slot.up) { "$owner is already up" }
        val ctx = base.forOwner(owner, 0)
        ctx.hold()
        ctx.activate()
        when (val r = lifecycle.bind(ctx, wired)) {
            is Result.Success -> Unit
            is Result.Failure -> {
                runCatching { lifecycle.unbind(ctx, wired) }
                ctx.close()
                return Result.Failure(RewireError.BindFailed(owner.id, r.error.message, rolledBack = true))
            }
        }
        slot.ctx = ctx
        slot.wired = wired
        slot.gen = 0
        return Result.Success(Unit)
    }

    /** Stops [owner]: unbind, then close its context. No-op when it is not up. */
    public fun down(owner: WiringOwner) {
        val slot = slots[owner] ?: return
        val ctx = slot.ctx ?: return
        runCatching { slot.lifecycle.unbind(ctx, slot.wired!!) }
        ctx.close()
        slot.ctx = null
        slot.wired = null
        slot.gen = -1
    }

    /** The live context of [owner], for hosts that need to keep using it (null when down). */
    public fun context(owner: WiringOwner): WiringContext? = slots[owner]?.ctx

    /** The value [PluginLifecycle.wire] produced for [owner]'s running generation. */
    public fun wired(owner: WiringOwner): Any? = slots[owner]?.wired

    override fun rewirable(): List<String> = slots.values.filter { it.up }.map { it.lifecycle.owner.id }

    override fun status(): List<String> = slots.values.map { slot ->
        val ctx = slot.ctx
        if (ctx == null) "${slot.lifecycle.owner} down" else "${slot.lifecycle.owner} gen ${slot.gen} ${ctx.counts()}"
    }

    override fun rewire(plugin: String, reconnect: Boolean): Result<RewireReport, RewireError> {
        if (busy) return Result.Failure(RewireError.InProgress)
        busy = true
        try {
            return base.services.rewireWindow { doRewire(plugin, reconnect) }
        } finally {
            busy = false
        }
    }

    private fun resolve(name: String): String? {
        val wanted = name.trim().lowercase()
        return knownPlugins.firstOrNull { it.lowercase() == wanted || it.lowercase() == "astera-$wanted" }
    }

    private fun unknown(name: String) = RewireError.UnknownPlugin(name, knownPlugins)

    private fun doRewire(name: String, reconnect: Boolean): Result<RewireReport, RewireError> {
        val id = resolve(name) ?: return Result.Failure(unknown(name))
        val target = slots[WiringOwner(id)]
            ?: return Result.Failure(RewireError.RestartRequired(id, "no rewire lifecycle yet (restart the server)"))
        if (!target.up) return Result.Failure(RewireError.RestartRequired(id, "plugin is not running"))
        if (reconnect) {
            val r = runCatching { target.lifecycle.reconnect() }
                .getOrElse { Result.Failure(LifecycleFailure("${it::class.simpleName}: ${it.message}")) }
            if (r is Result.Failure) return Result.Failure(RewireError.ReconnectFailed(id, r.error.message))
        }

        val plan = plan(target.lifecycle.owner)
        val oldCtx = plan.associateWith { slots.getValue(it).ctx!! }
        val oldWired = plan.associateWith { slots.getValue(it).wired!! }
        val before = plan.associateWith { oldCtx.getValue(it).counts() }

        // 2. stage generation N+1 next to the running one.
        val staged = linkedMapOf<WiringOwner, Pair<WiringContext, Any>>()
        for (owner in plan) {
            val slot = slots.getValue(owner)
            val ctx = oldCtx.getValue(owner).forOwner(owner, slot.gen + 1)
            ctx.hold()
            val result = runCatching { slot.lifecycle.wire(ctx) }
                .getOrElse { Result.Failure(LifecycleFailure("${it::class.simpleName}: ${it.message}")) }
            when (result) {
                is Result.Success -> staged[owner] = ctx to result.value
                is Result.Failure -> {
                    ctx.close()
                    staged.values.reversed().forEach { it.first.close() }
                    return Result.Failure(RewireError.WireFailed(owner.id, result.error.message))
                }
            }
        }

        // 3. commit: stop generation N, start generation N+1.
        plan.asReversed().forEach { runCatching { slots.getValue(it).lifecycle.unbind(oldCtx.getValue(it), oldWired.getValue(it)) } }
        val bound = mutableListOf<WiringOwner>()
        for (owner in plan) {
            val (ctx, wired) = staged.getValue(owner)
            val slot = slots.getValue(owner)
            ctx.activate()
            val result = runCatching { slot.lifecycle.bind(ctx, wired) }
                .getOrElse { Result.Failure(LifecycleFailure("${it::class.simpleName}: ${it.message}")) }
            if (result is Result.Failure) {
                bound.asReversed().forEach { b ->
                    runCatching { slots.getValue(b).lifecycle.unbind(staged.getValue(b).first, staged.getValue(b).second) }
                }
                staged.values.reversed().forEach { it.first.close() }
                val restored = plan.all { o ->
                    runCatching { slots.getValue(o).lifecycle.bind(oldCtx.getValue(o), oldWired.getValue(o)) }
                        .getOrNull() is Result.Success
                }
                return Result.Failure(RewireError.BindFailed(owner.id, result.error.message, restored))
            }
            bound += owner
        }

        // 4. retire generation N: everything stamped with the old tags goes.
        val changes = mutableListOf<RewireChange>()
        val warnings = mutableListOf<String>()
        plan.asReversed().forEach { oldCtx.getValue(it).close(drainOneShots = true) }
        for (owner in plan) {
            val slot = slots.getValue(owner)
            val (ctx, wired) = staged.getValue(owner)
            val fromGen = slot.gen
            slot.ctx = ctx
            slot.wired = wired
            slot.gen = fromGen + 1
            val after = ctx.counts()
            val was = before.getValue(owner)
            changes += RewireChange(owner, fromGen, slot.gen, was, after)
            if (after.services == 0 && was.services > 0) warnings += "$owner: services fell ${was.services} -> 0"
            if (after.tasks == 0 && was.tasks > 0) warnings += "$owner: tasks fell ${was.tasks} -> 0"
            if (after.subscriptions == 0 && was.subscriptions > 0) warnings += "$owner: subscriptions fell ${was.subscriptions} -> 0"
            if (after.listeners == 0 && was.listeners > 0) warnings += "$owner: listeners fell ${was.listeners} -> 0"
        }
        return Result.Success(RewireReport(changes, warnings))
    }

    /** [target] plus running plugins that depend on it, transitively, in registration (load) order. */
    private fun plan(target: WiringOwner): List<WiringOwner> {
        val hit = linkedSetOf(target)
        var grew = true
        while (grew) {
            grew = false
            for ((owner, slot) in slots) {
                if (owner !in hit && slot.up && slot.lifecycle.dependsOn.any { it in hit }) {
                    hit += owner
                    grew = true
                }
            }
        }
        return slots.keys.filter { it in hit }
    }
}
