package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcBackendId
import com.astarworks.astera.core.domain.entity.NpcError
import com.astarworks.astera.core.domain.entity.NpcTraitSpec
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Always-available NPC adapter backed by [SpawnSpec.ArmorStandNpc] /
 * [SpawnSpec.DisplayNpc] only — the Wave 5l "Minimal core" tier of ADR-0082.
 *
 * # What it can spawn
 *
 * Concretely: [SpawnSpec.ArmorStandNpc] and [SpawnSpec.DisplayNpc]. NOT
 * [SpawnSpec.CitizensNpc] (returns false from [supports]; the spawner
 * router then either falls through to the Citizens adapter or returns
 * [SpawnError.NoSpawnableBackend]).
 *
 * # Traits
 *
 * [NpcTraitSpec.Lookable] is implemented as a tracked book-keeping flag
 * (the Wave 6b Paper adapter ships the actual head-rotation tick handler);
 * [NpcTraitSpec.HologramAttachment] is also recorded (the spawn position
 * of the attached hologram is left to the caller / the Paper adapter to
 * keep in sync). [NpcTraitSpec.Pathing] is a no-op — armor stands don't
 * pathfind. The unsupported trait does NOT raise an error from this adapter
 * (consistent with the "Minimal" philosophy: silent best-effort + a debug
 * log line); use the Citizens adapter when you need real pathing.
 *
 * # State
 *
 * Pure in-memory state for Wave 5l (no Bukkit entity is actually spawned —
 * that lands in Wave 6b's `PaperArmorStandSpawner`). The adapter records
 * the handle + spawn position so [com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner.all]
 * returns sane data and tests can assert "yes, spawn was called".
 */
public class MinimalNpcAdapter : INpcAdapter {

    private data class TrackedNpc(
        val world: WorldId,
        val position: Vec3,
        val spec: SpawnSpec,
        val traits: MutableList<NpcTraitSpec> = mutableListOf(),
    )

    private val tracked: ConcurrentHashMap<EntityHandle, TrackedNpc> = ConcurrentHashMap()

    public companion object {
        public val BACKEND_ID: NpcBackendId = NpcBackendId.MINIMAL
    }

    override fun backendId(): NpcBackendId = BACKEND_ID

    override fun supports(spec: SpawnSpec): Boolean = when (spec) {
        is SpawnSpec.ArmorStandNpc -> true
        is SpawnSpec.DisplayNpc -> true
        // Minimal can't host Citizens-specific behaviour (skin override, real
        // pathing, type-key polymorphism). Router asks Citizens first.
        is SpawnSpec.CitizensNpc -> false
        // Mobs / projectiles aren't NPCs — the spawner routes those elsewhere
        // (Wave 6a PaperMobSpawner / PaperProjectileService).
        is SpawnSpec.Mob -> false
        is SpawnSpec.Projectile -> false
    }

    override fun spawn(
        world: WorldId,
        position: Vec3,
        spec: SpawnSpec,
    ): Result<EntityHandle, SpawnError> {
        if (!supports(spec)) {
            return Result.failure(SpawnError.NoSpawnableBackend)
        }
        val handle = EntityHandle(id = UUID.randomUUID(), backendId = BACKEND_ID.value)
        tracked[handle] = TrackedNpc(world = world, position = position, spec = spec)
        return Result.success(handle)
    }

    override fun applyTraits(
        handle: EntityHandle,
        traits: List<NpcTraitSpec>,
    ): Result<Unit, NpcError> {
        if (handle.backendId != BACKEND_ID.value) {
            return Result.failure(NpcError.NotOwnedByAdapter)
        }
        val entry = tracked[handle] ?: return Result.failure(NpcError.HandleNotFound)
        // Silently ignore Pathing (intentional per docstring). Lookable +
        // HologramAttachment are tracked so reload() / introspection can
        // see them.
        for (trait in traits) {
            when (trait) {
                is NpcTraitSpec.Pathing -> { /* no-op: armor stand can't path */ }
                is NpcTraitSpec.Lookable, is NpcTraitSpec.HologramAttachment -> {
                    entry.traits += trait
                }
            }
        }
        return Result.success(Unit)
    }

    override fun reload(handle: EntityHandle) {
        // No cached visuals to refresh in Wave 5l — the Wave 6b Paper layer
        // re-reads the held item / display name from the SpawnSpec stored
        // in [tracked]. Phase 1 here is intentionally a no-op.
    }

    override fun despawn(handle: EntityHandle): Result<Unit, NpcError> {
        if (handle.backendId != BACKEND_ID.value) {
            return Result.failure(NpcError.NotOwnedByAdapter)
        }
        val removed = tracked.remove(handle)
        return if (removed != null) {
            Result.success(Unit)
        } else {
            Result.failure(NpcError.HandleNotFound)
        }
    }

    /** Test-only: snapshot of every currently-tracked handle. */
    public fun all(): Collection<EntityHandle> = tracked.keys.toList()

    /** Test-only: clear all state (between tests). */
    public fun clear() {
        tracked.clear()
    }
}
