package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.MobAiHint
import com.astarworks.astera.core.domain.mob.MobBackendId
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobSkillBinding
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Backend that materialises [MobSpec] instances into running entities
 * (Wave 5pve, ADR-0085).
 *
 * Same two-tier philosophy as Wave 5l's [com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter]
 * — Astera ships **two** adapters and the [IMobSpawner] router picks the
 * highest-priority available one at spawn time:
 *
 *  - `MinimalMobAdapter` (Core, always available, `backendId = "minimal"`)
 *    — backs every [MobSpec] using ArmorStand + Display entities + the
 *    `MobSkillRouter` for skill binding routing. AI hint support is limited
 *    to [MobAiHint.Default] / [MobAiHint.Stationary]; richer hints fall back
 *    to Default.
 *  - `MythicMobsMobAdapter` (Wave 6b skeleton → Phase 4 full impl,
 *    `backendId = "mythicmobs"`) — bridges to the MythicMobs plugin when
 *    installed. `isAvailable()` returns `false` in vanilla deployments so
 *    the router cleanly falls through to Minimal.
 *
 * # Backend uniqueness
 *
 * Every adapter MUST return a unique [backendId] value. Konsist enforces
 * this structurally via `IMobAdapter implementations must declare
 * backendId() uniquely` (ADR-0085 §"Konsist rules"). The id is used in
 * [EntityHandle.backendId] so `despawn` / `applySkill` can route the handle
 * back to its owning adapter without keeping a parallel handle → adapter map.
 *
 * # Vendor isolation
 *
 * Konsist enforces `core.domain.mob.* must not import io.lumine.mythic.*`
 * — MythicMobs API references live only in the Wave 6b Paper adapter's
 * `adapter/paper/` package, never in domain or in this port.
 */
public interface IMobAdapter {

    /**
     * Stable identifier for this backend. Returned value goes into every
     * [EntityHandle.backendId] this adapter mints.
     */
    public fun backendId(): MobBackendId

    /**
     * Whether this adapter is currently usable. The Minimal adapter always
     * returns true; the MythicMobs adapter returns true only when the
     * MythicMobs plugin is loaded and reachable.
     *
     * The [IMobSpawner] router consults this **before** dispatching so a
     * registered-but-unavailable adapter never sees a spawn call.
     */
    public fun isAvailable(): Boolean

    /**
     * Spawn a mob according to [spec] at [position] in [world]. Caller
     * (router) has already verified [isAvailable] returns true.
     *
     * Returns [MobError.BackendError] if the underlying backend rejected the
     * spawn (world unloaded between availability check and spawn, MythicMobs
     * mob translation failed, …). Adapters must not throw — wrap any
     * platform exception in `BackendError`.
     */
    public fun spawn(
        world: WorldId,
        position: Vec3,
        spec: MobSpec,
    ): Result<EntityHandle, MobError>

    /**
     * Apply a [MobSkillBinding] to a previously-spawned mob — typically
     * called by `MobSkillRouter` when a binding's trigger condition is
     * detected (player enters range, HP threshold crossed, …).
     *
     * For the Minimal adapter this publishes `SkillCastRequested` so combat
     * resolves the skill identically to a player-fired cast. The MythicMobs
     * adapter may delegate to the MythicMobs skill engine instead.
     */
    public fun applySkill(
        handle: EntityHandle,
        binding: MobSkillBinding,
    ): Result<Unit, MobError>

    /**
     * Remove a tracked mob. Idempotent — despawning an already-removed
     * handle returns [MobError.BackendError] in the strict adapter, or
     * `Success(Unit)` in tolerant impls (the router is the source of truth
     * for "has this handle been despawned").
     */
    public fun despawn(handle: EntityHandle): Result<Unit, MobError>

    /**
     * Whether the mob behind [handle] still exists and is alive (ADR-0249). A PvE encounter polls
     * this so a mob that fell out of the world or was removed by the host does not block the
     * wave forever. Defaults to `true` (no way to know).
     */
    public fun isAlive(handle: EntityHandle): Boolean = true

    /**
     * Whether this adapter can honour the given AI [hint] for newly-spawned
     * mobs. The router does NOT consult this for adapter selection — it's
     * a capability advertisement so the spawner can warn at registration
     * time when a MobSpec asks for a hint no adapter supports. Defaults to
     * honouring [MobAiHint.Default] only; richer adapters override.
     */
    public fun supportsAi(hint: MobAiHint): Boolean = hint == MobAiHint.Default
}
