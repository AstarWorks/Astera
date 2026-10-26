package com.astarworks.astera.core.domain.entity

import com.astarworks.astera.core.domain.world.WorldId

/**
 * Sealed failure mode for [com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner.spawn]
 * (and despawn / teleport — they share the same error vocabulary).
 *
 * Per ADR-0001: application APIs never throw — they return `Result<T, E>`
 * with a sealed error type so callers can handle each case exhaustively.
 */
public sealed interface SpawnError {

    /** No adapter recognises the [SpawnSpec]'s `typeKey`. */
    public data object UnknownTypeKey : SpawnError

    /** No adapter handles this spec. */
    public data object NoSpawnableBackend : SpawnError

    /** The target [com.astarworks.astera.core.domain.world.WorldId] is not loaded. */
    public data class WorldNotLoaded(public val world: WorldId) : SpawnError
}
