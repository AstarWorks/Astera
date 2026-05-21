package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter
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
 * Skeleton [IMobAdapter] for the MythicMobs bridge (Wave 5pve, ADR-0085).
 *
 * Wave 5pve commits the port surface only — every call returns
 * [MobError.BackendError] / [isAvailable] = false. The Wave 6b Paper
 * subclass (`PaperMythicMobsMobAdapter`) will:
 *
 *  1. Detect MythicMobs presence at boot (`Bukkit.getPluginManager().getPlugin("MythicMobs")`).
 *  2. Translate Astera [MobSpec] → MythicMobs MobSpec via a dedicated
 *     translation layer (`MythicMobsSpecTranslator`), mapping equipment,
 *     skill bindings, AI hints, and spawn conditions onto MythicMobs'
 *     native vocabulary.
 *  3. Delegate spawn / applySkill / despawn to the MythicMobs API surface.
 *  4. Map MythicMobs skill ids back to Astera SkillIds via a config-driven
 *     mapping table (so MythicMobs's "Lightning" skill can map to Astera's
 *     `astera:lightning-bolt` and combat resolves it uniformly).
 *
 * # Why a skeleton in Phase 1
 *
 * Per ADR-0085 §"Two-tier philosophy", the port commitment is what enables
 * the router pattern. With the skeleton present at priority > 0,
 * `MobSpawnerRouter` exercises the "highest-priority available adapter"
 * code path even in Phase 1 (the skeleton reports unavailable, so the
 * router falls through to Minimal). Phase 4 / Wave 6b activates the real
 * impl without changing any registration code.
 *
 * # Vendor isolation
 *
 * This file deliberately does NOT import `io.lumine.mythic.*`. Konsist
 * enforces `core.domain.mob.* must not import io.lumine.mythic.*` and we
 * extend that posture to the in-Core skeleton — MythicMobs API references
 * live ONLY in the Wave 6b Paper adapter under `<plugin>/adapter/paper/mob/`.
 */
public class MythicMobsMobAdapter : IMobAdapter {

    public companion object {
        public val BACKEND_ID: MobBackendId = MobBackendId.MYTHICMOBS

        private const val UNAVAILABLE_CAUSE: String =
            "MythicMobs adapter not active (Phase 1 skeleton; Wave 6b Paper subclass " +
                "activates when MythicMobs plugin is installed)."
    }

    override fun backendId(): MobBackendId = BACKEND_ID

    /**
     * Always `false` in Phase 1 — the skeleton never claims to be available.
     * The Wave 6b Paper subclass overrides to return `true` when MythicMobs
     * is loaded.
     */
    override fun isAvailable(): Boolean = false

    override fun spawn(
        world: WorldId,
        position: Vec3,
        spec: MobSpec,
    ): Result<EntityHandle, MobError> = Result.failure(MobError.BackendError(UNAVAILABLE_CAUSE))

    override fun applySkill(
        handle: EntityHandle,
        binding: MobSkillBinding,
    ): Result<Unit, MobError> = Result.failure(MobError.BackendError(UNAVAILABLE_CAUSE))

    override fun despawn(handle: EntityHandle): Result<Unit, MobError> =
        Result.failure(MobError.BackendError(UNAVAILABLE_CAUSE))

    /**
     * Advertises full AI support so the Wave 6b subclass doesn't need to
     * override — Phase 1 callers consult `isAvailable()` first, so this
     * never gates a real spawn.
     */
    override fun supportsAi(hint: MobAiHint): Boolean = true
}
