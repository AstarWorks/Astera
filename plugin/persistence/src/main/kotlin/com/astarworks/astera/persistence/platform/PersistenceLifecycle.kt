package com.astarworks.astera.persistence.platform

import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.platform.wiring.LifecycleFailure
import com.astarworks.astera.core.platform.wiring.PluginLifecycle
import com.astarworks.astera.core.platform.wiring.WiringContext

/** What a Persistence generation produced: the attribute store its join hook reloads from (null when not connected). */
internal class PersistenceWired(val attributeStore: IPlayerAttributeContainerStore?)

/**
 * Persistence as a rewirable plugin (ADR-0311 P3), split into two halves:
 *
 * - connect: [PersistenceRuntime] owns the pool and survives every generation. `connect()` is run in `onLoad`
 *   (ADR-0195: overlays must exist before any consumer is built) and again by [wire] when an earlier attempt failed.
 * - overlay: [wire] builds the repositories of this generation and registers them at priority 100 under the
 *   generation's tag. Generation 0 is wired by the plugin in `onLoad` and adopted by the coordinator.
 *
 * Every plugin that resolves a Persistence-overlaid port lists this owner in its `dependsOn`, so a Persistence
 * rewire rebuilds them against the new repositories. Feature plugins rewired alone keep resolving this
 * generation's overlays (priority 100 beats their in-memory defaults; the new feature generation never sees a
 * default).
 */
internal class PersistenceLifecycle(private val runtime: PersistenceRuntime) : PluginLifecycle<PersistenceWired> {

    override val owner: WiringOwner = OWNER
    override val dependsOn: List<WiringOwner> = emptyList()

    override fun wire(ctx: WiringContext): Result<PersistenceWired, LifecycleFailure> {
        when (val r = runtime.connect()) {
            is Result.Failure -> return r
            is Result.Success -> Unit
        }
        ctx.registrations += runtime.overlay(ctx.services)
        return Result.Success(PersistenceWired(runtime.attributeStore))
    }

    override fun bind(ctx: WiringContext, wired: PersistenceWired): Result<Unit, LifecycleFailure> {
        // F3-4: the lobby and the game server share one player row but each JVM caches it. A player arriving on this
        // backend re-reads the row first, so the wallet / last-match written on the other backend is what shows.
        val store = wired.attributeStore ?: return Result.Success(Unit)
        ctx.eventBus.subscribe(PlayerJoinedServer::class) { store.reload(it.playerId) }
        return Result.Success(Unit)
    }

    override fun unbind(ctx: WiringContext, wired: PersistenceWired) {
        // The join subscription is owned by the generation's tag; WiringContext.close cancels it.
    }

    override fun reconnect(): Result<Unit, LifecycleFailure> = runtime.reconnect()

    companion object {
        val OWNER: WiringOwner = WiringOwner("Astera-Persistence")
    }
}
