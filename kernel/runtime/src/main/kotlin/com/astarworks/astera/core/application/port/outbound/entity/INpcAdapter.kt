package com.astarworks.astera.core.application.port.outbound.entity

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcBackendId
import com.astarworks.astera.core.domain.entity.NpcError
import com.astarworks.astera.core.domain.entity.NpcTraitSpec
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Backend that handles NPC-shaped [SpawnSpec] variants. Per ADR-0082
 * (Entity spawning + NPC two-tier), Astera ships **two** adapters:
 *
 *  - `MinimalNpcAdapter` (Core, always available) — backs
 *    [SpawnSpec.ArmorStandNpc] / [SpawnSpec.DisplayNpc] with vanilla
 *    armor-stand / display entities. Pathing / Citizens-only traits are
 *    no-ops; [SpawnSpec.CitizensNpc] returns [SpawnError.NoSpawnableBackend].
 *  - `CitizensNpcAdapter` (Items plugin, Wave 6b Paper impl) — backs every
 *    NPC variant via the Citizens API when Citizens is installed.
 *
 * Adapters register themselves on [com.astarworks.astera.core.application.services.ServiceRegistry]
 * under `INpcAdapter`. The router-style `IEntitySpawner` enumerates them on
 * spawn and picks the highest-priority adapter that [supports] the spec.
 *
 * # Backend uniqueness
 *
 * Every adapter MUST return a unique [backendId] value — Konsist enforces this
 * structurally (`INpcAdapter implementations must declare backendId() uniquely`,
 * ADR-0082 §"Konsist rules"). The id is used in [EntityHandle.backendId] so
 * `despawn` / `applyTraits` can route the handle back to its owning adapter
 * without keeping a parallel handle → adapter map.
 */
public interface INpcAdapter {

    /**
     * Stable identifier for this backend. Returned value goes into every
     * [EntityHandle.backendId] this adapter mints.
     */
    public fun backendId(): NpcBackendId

    /**
     * Whether this backend can spawn [spec]. Used by the router (`IEntitySpawner`)
     * to choose between Citizens and Minimal at runtime. Implementations
     * should answer based on **both** the spec variant AND any backend
     * availability check (e.g. Citizens plugin enabled).
     */
    public fun supports(spec: SpawnSpec): Boolean

    /**
     * Spawn the NPC. Caller has already verified [supports] returns true.
     * Returning a failure means the spawn surface-attempted but couldn't
     * complete (world unloaded between supports() and spawn(), etc.).
     */
    public fun spawn(
        world: WorldId,
        position: Vec3,
        spec: SpawnSpec,
    ): Result<EntityHandle, SpawnError>

    /**
     * Apply [traits] to a previously-spawned NPC. Traits the adapter
     * doesn't understand return [NpcError.UnsupportedTrait]; the caller
     * decides whether to fail or ignore.
     */
    public fun applyTraits(
        handle: EntityHandle,
        traits: List<NpcTraitSpec>,
    ): Result<Unit, NpcError>

    /**
     * Refresh visuals — skin texture / display name — for an NPC whose
     * underlying configuration changed (e.g. operator changed the
     * binding's `displayName`). No-op for backends that don't cache
     * visuals.
     */
    public fun reload(handle: EntityHandle)

    /** Remove the NPC. Idempotent. */
    public fun despawn(handle: EntityHandle): Result<Unit, NpcError>
}
