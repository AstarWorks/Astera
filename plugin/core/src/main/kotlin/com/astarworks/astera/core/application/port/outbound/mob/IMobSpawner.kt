package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Front-door router for mob spawning (Wave 5pve, ADR-0085).
 *
 * Resolves a [MobTypeId] against `IMobRegistry`, picks the highest-priority
 * available [IMobAdapter], and dispatches the spawn. Publishes
 * [com.astarworks.astera.core.domain.event.MobSpawned] on success.
 *
 * Application code calls this port — never an adapter directly — so the
 * MythicMobs / Minimal selection stays opaque. The router treats each
 * adapter's `isAvailable()` as authoritative; a registered adapter that
 * reports unavailable is skipped (the MythicMobs skeleton in Phase 1 always
 * reports false, so the router cleanly falls through to Minimal).
 *
 * # Why a separate port from [IMobAdapter]
 *
 * Adapters answer "can I spawn this raw MobSpec?". The spawner answers
 * "given a registry id, spawn it". Splitting the surfaces:
 *  - keeps adapters from needing the registry
 *  - lets test harnesses register a single fake adapter and bypass the registry
 *  - models the routing decision explicitly (Wave 6a Paper integration plugs
 *    into the same surface)
 */
public interface IMobSpawner {

    /**
     * Spawn a mob registered as [mobTypeId] at [position] in [world].
     *
     * Failure modes:
     *  - [MobError.UnknownMobType]: no [com.astarworks.astera.core.domain.mob.MobSpec]
     *    registered under that id.
     *  - [MobError.NoAdapterAvailable]: every registered [IMobAdapter] returned
     *    `isAvailable() = false`.
     *  - [MobError.BackendError]: the selected adapter's spawn call failed.
     */
    public fun spawn(
        world: WorldId,
        position: Vec3,
        mobTypeId: MobTypeId,
    ): Result<EntityHandle, MobError>

    /**
     * Remove a previously-spawned mob. The router uses the handle's
     * [EntityHandle.backendId] to find the owning adapter; returns
     * [MobError.NoAdapterAvailable] if no registered adapter matches that
     * backend id.
     */
    public fun despawn(handle: EntityHandle): Result<Unit, MobError>

    /**
     * The adapter the router would pick for the next spawn, given current
     * availability. Returns `null` if no adapter is available. Used by
     * admin commands + the `/astera-mob status` introspection (Wave 6b).
     */
    public fun activeBackendId(): com.astarworks.astera.core.domain.mob.MobBackendId?
}
