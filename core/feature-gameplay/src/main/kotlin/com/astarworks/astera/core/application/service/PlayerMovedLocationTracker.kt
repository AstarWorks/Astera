package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup
import com.astarworks.astera.core.application.port.outbound.PlayerLocation
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IPlayerLocationLookup] (Wave 5complete, ADR-0117).
 *
 * Subscribes to [PlayerMoved] on `start()` and keeps a per-player snapshot
 * of the latest `(world, to)` pair. Wave 6b's Paper adapter overlays a
 * direct-from-`IMcServer` lookup at priority 100 — until then, this tracker
 * is the only way to answer "where is player X" without a Paper handle.
 *
 * # Thread-safety
 *
 * `ConcurrentHashMap` backs the store; the subscriber lambda runs on
 * whichever thread the event-bus dispatches from (in Phase 1 — the main
 * thread).
 *
 * # Wiring
 *
 * Owned by `AsteraCorePlugin.onEnable`; `start()` returns the subscription
 * so the plugin can cancel on `onDisable`.
 */
public class PlayerMovedLocationTracker(
    private val eventBus: IDomainEventBus,
) : IPlayerLocationLookup {

    private val locations: ConcurrentHashMap<PlayerId, PlayerLocation> = ConcurrentHashMap()

    private var subscription: IDomainEventBus.Subscription? = null

    public fun start(): IDomainEventBus.Subscription {
        val sub = eventBus.subscribe(PlayerMoved::class, EventDelivery.Global) { event ->
            locations[event.playerId] = PlayerLocation(world = event.world, position = event.to)
        }
        subscription = sub
        return sub
    }

    public fun stop() {
        subscription?.cancel()
        subscription = null
        locations.clear()
    }

    override fun locate(playerId: PlayerId): PlayerLocation? = locations[playerId]

    /**
     * Test / lifecycle helper — purge entries for [playerId]. The Paper
     * adapter calls this on `PlayerQuit` so stale rows don't accumulate
     * across long-running sessions.
     */
    public fun forget(playerId: PlayerId) {
        locations.remove(playerId)
    }

    /** Test helper: number of tracked players (excludes pruned entries). */
    public fun trackedCount(): Int = locations.size
}
