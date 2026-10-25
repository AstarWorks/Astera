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

/**
 * Edge-tier NPC adapter that delegates to Citizens. **Wave 5l ships the
 * skeleton only** — the Paper-side implementation (`PaperCitizensNpcAdapter`
 * wrapping `net.citizensnpcs.api.npc.NPC` / `NPCRegistry`) lands in Wave 6b.
 *
 * Why a skeleton lives in Core now (ADR-0082):
 *  1. Konsist test `INpcAdapter implementations must declare backendId()
 *     uniquely` needs both adapters present at test-compile time to verify
 *     no collision exists.
 *  2. Wave 5g's `IShopVendor` PoC (Phase 4) and Wave 5l's `IEntitySpawner`
 *     router code can early-bind the [INpcAdapter] type and rely on
 *     priority + [isAvailable] gating.
 *  3. Provides a stable rejection path (return [SpawnError.NoSpawnableBackend]
 *     /[NpcError.BackendUnavailable]) for environments without Citizens —
 *     no `try { Class.forName(...) }` runtime sniff needed by callers.
 *
 * # Availability
 *
 * Until the Wave 6b Paper subclass overrides [isAvailable] to return true
 * when the Citizens plugin is loaded, this skeleton always returns false.
 * The router treats `supports(...) = false`, so the spawner falls through
 * to the Minimal adapter for compatible specs and returns
 * [SpawnError.NoSpawnableBackend] for Citizens-only specs.
 */
public open class CitizensNpcAdapter : INpcAdapter {

    public companion object {
        public val BACKEND_ID: NpcBackendId = NpcBackendId.CITIZENS
    }

    /**
     * Whether Citizens is loaded and the adapter can service real spawns.
     * Subclassed in Wave 6b (`PaperCitizensNpcAdapter`) to check
     * `Bukkit.getPluginManager().getPlugin("Citizens")?.isEnabled == true`.
     */
    public open fun isAvailable(): Boolean = false

    override fun backendId(): NpcBackendId = BACKEND_ID

    override fun supports(spec: SpawnSpec): Boolean {
        if (!isAvailable()) return false
        return when (spec) {
            // Citizens supports the entire NPC variant axis natively.
            is SpawnSpec.CitizensNpc, is SpawnSpec.ArmorStandNpc, is SpawnSpec.DisplayNpc -> true
            // Mobs / projectiles route to other backends; Citizens has no
            // sensible answer for them.
            is SpawnSpec.Mob, is SpawnSpec.Projectile -> false
        }
    }

    override fun spawn(
        world: WorldId,
        position: Vec3,
        spec: SpawnSpec,
    ): Result<EntityHandle, SpawnError> {
        // Wave 5l: skeleton always refuses. Wave 6b Paper subclass overrides
        // to do the real Citizens NPCRegistry.createNPC(...) call.
        return if (isAvailable()) {
            // Subclass should have overridden this — fall through means
            // "isAvailable lied". Treat as a logic bug, but stay typed.
            Result.failure(SpawnError.NoSpawnableBackend)
        } else {
            Result.failure(SpawnError.NoSpawnableBackend)
        }
    }

    override fun applyTraits(
        handle: EntityHandle,
        traits: List<NpcTraitSpec>,
    ): Result<Unit, NpcError> {
        return Result.failure(NpcError.BackendUnavailable)
    }

    override fun reload(handle: EntityHandle) {
        // Skeleton: nothing to reload.
    }

    override fun despawn(handle: EntityHandle): Result<Unit, NpcError> {
        return if (handle.backendId == BACKEND_ID.value) {
            // No tracked state in the skeleton; report not-found.
            Result.failure(NpcError.HandleNotFound)
        } else {
            Result.failure(NpcError.NotOwnedByAdapter)
        }
    }
}
