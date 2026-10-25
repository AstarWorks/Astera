package com.astarworks.astera.core.domain.world

/**
 * Why a chunk was unloaded (Wave 5world-events, ADR-0111).
 *
 * Used by [com.astarworks.astera.core.domain.event.ChunkUnloaded] to keep the
 * spatial index / entity registry / region permission cache consistent:
 *
 *  - [PlayerLeftRange] is the normal case — subscribers must drop their
 *    chunk-scoped caches.
 *  - [WorldUnload] indicates an entire world tear-down (Wave 5pve-survival
 *    island unload, lobby world swap). Subscribers may need to flush more
 *    aggressively than a single-chunk eviction.
 *  - [ForcedByAdmin] is admin-issued (`/world unload`, debug overlay action).
 *    Anticheat / replay capture may suppress chunk-related warnings while
 *    this drains.
 *
 * Enum rather than sealed because no entry needs a payload — the world / chunk
 * coords are already on the event.
 */
public enum class ChunkUnloadReason {
    PLAYER_LEFT_RANGE,
    WORLD_UNLOAD,
    FORCED_BY_ADMIN,
}
