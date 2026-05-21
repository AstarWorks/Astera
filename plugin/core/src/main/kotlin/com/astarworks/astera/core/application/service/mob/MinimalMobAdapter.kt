package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.MobSkillTriggered
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.MobAiHint
import com.astarworks.astera.core.domain.mob.MobBackendId
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobSkillBinding
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Always-available [IMobAdapter] backed by in-memory tracking + the existing
 * Wave 5l entity-display vocabulary (Wave 5pve, ADR-0085).
 *
 * # What it does in Phase 1
 *
 * Pure in-memory bookkeeping: spawn records the handle + position + spec;
 * despawn removes it; [applySkill] publishes [SkillCastRequested] (which
 * combat already subscribes to) plus [MobSkillTriggered] (for mob-aware
 * subscribers like analytics).
 *
 * The Wave 6b Paper subclass (`PaperMinimalMobAdapter`) overlays this with
 * real ArmorStand + Display entity spawns; the routing + skill-publish
 * shape stays identical, so combat resolves a mob-cast skill exactly the
 * same way it resolves a player-cast skill.
 *
 * # AI support
 *
 * [supportsAi] honours [MobAiHint.Default] and [MobAiHint.Stationary] only.
 * Richer hints (Pathfind / Flee / Aggressive) silently fall back to Default
 * at spawn time — this adapter doesn't pretend to do path-finding. Callers
 * that need rich AI route through the MythicMobs adapter (Phase 4).
 *
 * # Threading
 *
 * Called only from the Paper main thread per Astera invariant. Uses a
 * concurrent map purely for crash-safety on shutdown.
 */
public class MinimalMobAdapter(
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) : IMobAdapter {

    private data class TrackedMob(
        val world: WorldId,
        val position: Vec3,
        val spec: MobSpec,
    )

    private val tracked: ConcurrentHashMap<EntityHandle, TrackedMob> = ConcurrentHashMap()

    public companion object {
        public val BACKEND_ID: MobBackendId = MobBackendId.MINIMAL
    }

    override fun backendId(): MobBackendId = BACKEND_ID

    override fun isAvailable(): Boolean = true

    override fun spawn(
        world: WorldId,
        position: Vec3,
        spec: MobSpec,
    ): Result<EntityHandle, MobError> {
        val handle = EntityHandle(id = UUID.randomUUID(), backendId = BACKEND_ID.value)
        tracked[handle] = TrackedMob(world, position, spec)
        return Result.success(handle)
    }

    override fun applySkill(
        handle: EntityHandle,
        binding: MobSkillBinding,
    ): Result<Unit, MobError> {
        val mob = tracked[handle]
            ?: return Result.failure(MobError.BackendError("handle not tracked by minimal adapter: $handle"))

        val tick = scheduler.currentTick()

        // Publish MobSkillTriggered — combat (Wave 6a) subscribes to this
        // event for mob-cast skill resolution, distinct from the
        // SkillCastRequested path used by item-driven player casts. The
        // separation matters because SkillCastRequested carries a non-null
        // actorId/sourceItemId (item-binding semantics) while mob casts
        // have neither; conflating them would require nullifying both
        // fields and reworking every existing subscriber.
        eventBus.publish(
            MobSkillTriggered(
                handle = handle,
                mobTypeId = mob.spec.id,
                skillId = binding.skillId,
                trigger = binding,
                atTick = tick,
            ),
        )
        return Result.success(Unit)
    }

    override fun despawn(handle: EntityHandle): Result<Unit, MobError> {
        val removed = tracked.remove(handle) != null
        return if (removed) Result.success(Unit)
        else Result.failure(MobError.BackendError("handle not tracked: $handle"))
    }

    override fun supportsAi(hint: MobAiHint): Boolean =
        hint == MobAiHint.Default || hint == MobAiHint.Stationary

    // ─── Test-helper surface (not part of IMobAdapter) ────────────────────

    /** Test-only: snapshot all currently-tracked handles. */
    public fun trackedHandles(): Set<EntityHandle> = tracked.keys.toSet()

    /** Test-only: lookup a tracked mob's id by handle. */
    public fun mobTypeOf(handle: EntityHandle): MobTypeId? = tracked[handle]?.spec?.id

    /** Test-only: drop all state. */
    public fun clear() {
        tracked.clear()
    }
}
