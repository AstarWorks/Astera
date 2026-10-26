package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Published just before a world is unloaded from the server (Wave L
 * 5hardening-complete / ADR-0129).
 *
 * # Why this event exists
 *
 * Phase 1 PoC closing: matches own per-match worlds (Wave 5d
 * `MatchWorld` + `IMatchWorldRepository`). If the server unloads a world
 * mid-match — operator typed `/world unload`, ArgoCD-triggered restart
 * interrupts a long match, world corruption forces a Bukkit
 * `WorldUnloadEvent` — the match must end cleanly: its participants
 * teleported back to lobby, ledger writes flushed, stats recorded.
 * Without this signal the match would be left as a phantom `ACTIVE` row
 * with no addressable world, and the next `BlockStateGateway.applyDamage`
 * call would NPE / silent-drop.
 *
 * Wave 6b's `PaperWorldUnloadAdapter` registers a Bukkit
 * `WorldUnloadEvent` listener and publishes this event before the actual
 * unload happens (Bukkit's hook runs BEFORE the world map is detached).
 * Phase 1 Core ships the event + a Match-side listener that ends the
 * match for the unloaded world; the actual Bukkit bridge lands later.
 *
 * # Contract
 *
 * Published **before** the world is unloaded from server memory. Subscribers
 * may still query the world via vendor-neutral gateways during the
 * handler. Cancelling the unload is **not** supported through this event
 * (Bukkit's `WorldUnloadEvent` is the cancel-point; this event is
 * post-cancel, pre-unload).
 */
public data class WorldUnloading(
    val worldId: WorldId,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
