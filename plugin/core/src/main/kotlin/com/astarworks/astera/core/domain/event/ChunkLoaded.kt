package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain-level "a chunk just came online" event (Wave 5world-events, ADR-0111).
 *
 * Subscribers:
 *
 *  - **Spatial index** (Wave 5n) — re-hydrate region permission cache for
 *    the newly-loaded chunk coords.
 *  - **Entity registry** — wake up persisted entity records whose home
 *    chunk just loaded.
 *  - **Custom block durability journal** (ADR-0090, Wave 5x) — populate
 *    in-memory per-chunk durability map from the Postgres overlay.
 *
 * Chunk coords are the canonical Minecraft chunk-grid pair
 * (`block.x shr 4`, `block.z shr 4`). The producer (Bukkit `ChunkLoadEvent`
 * → adapter) lands in Wave 6.
 */
public data class ChunkLoaded(
    val world: WorldId,
    val chunkX: Int,
    val chunkZ: Int,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
