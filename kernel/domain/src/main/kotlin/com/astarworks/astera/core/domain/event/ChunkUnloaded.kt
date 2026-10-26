package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.world.ChunkUnloadReason
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain-level "a chunk just unloaded" event (Wave 5world-events, ADR-0111).
 *
 * Companion to [ChunkLoaded]. Subscribers must drop their chunk-scoped
 * caches; [reason] tells them whether the unload was the normal
 * "player-left-range" eviction or something more aggressive (entire world
 * teardown, admin force).
 *
 * The producer (Bukkit `ChunkUnloadEvent` → adapter) lands in Wave 6.
 */
public data class ChunkUnloaded(
    val world: WorldId,
    val chunkX: Int,
    val chunkZ: Int,
    val reason: ChunkUnloadReason,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
