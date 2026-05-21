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

    /**
     * No adapter currently registered can spawn this spec. Typical case:
     * a [SpawnSpec.CitizensNpc] is requested but Citizens is not installed
     * and the spec is not compatible with the Minimal adapter's fallback.
     */
    public data object NoSpawnableBackend : SpawnError

    /** The target [com.astarworks.astera.core.domain.world.WorldId] is not loaded. */
    public data class WorldNotLoaded(public val world: WorldId) : SpawnError
}

/**
 * Failure mode for [com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter.applyTraits]
 * / `despawn`. Distinct from [SpawnError] because traits can fail in ways
 * that spawning cannot (e.g. an unsupported trait variant on the Minimal
 * adapter).
 */
public sealed interface NpcError {

    /** The handle's `backendId` doesn't match this adapter. */
    public data object NotOwnedByAdapter : NpcError

    /** The handle refers to an NPC the adapter no longer tracks. */
    public data object HandleNotFound : NpcError

    /** A trait variant is not supported by this adapter. */
    public data class UnsupportedTrait(public val traitClass: String) : NpcError

    /**
     * The adapter's backend plugin (Citizens, etc.) is not currently loaded.
     * Returned by edge adapters whose backend is a soft-dep — the in-process
     * [com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter]
     * instance stays registered (so registry priorities are stable) but
     * every call fails until the soft-dep becomes available.
     */
    public data object BackendUnavailable : NpcError
}
