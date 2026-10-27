package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Output of [VfxTimeline.plan]: everything the runtime needs, with no further math.
 * All positions are OFFSETS from [origin] (display entities are spawned at the anchor
 * and everything else lives in the display `transformation`), so a plan stays valid
 * when a `follow` anchor moves: the runtime just teleports the entities.
 */
public data class VfxPlan(
    public val origin: Vec3,
    public val durationTicks: Int,
    public val follow: Boolean,
    public val elements: List<VfxElementPlan>,
    public val followTeleportTicks: Int? = null,
    /** Opt-in native attachment for fixed displays following a player anchor. */
    public val attachToAnchor: Boolean = false,
    /** Replace an earlier following effect in this group on the same entity and world. */
    public val replaceGroup: String? = null,
    /** ADR-0276: the bone / weapon displays form one performance that owns the caster's hand (and body). */
    public val performance: Boolean = false,
    /** Effect ticks on which performance elements jump (interpolation 0); sent as one bundle per viewer. */
    public val cutTicks: Set<Int> = emptySet(),
) {
    /** Number of display entities the plan spawns (particle slots spawn none). */
    public val displayCount: Int get() = elements.count { it.kind !is VfxElementKind.Particle }
}

/** One slot of one element. Ticks are effect-relative (0 = the tick the effect starts). */
public data class VfxElementPlan(
    public val elementId: String,
    public val slot: Int,
    /** Kind with the initial content already resolved (a swap keyframe at t = 0 replaces the block / text). */
    public val kind: VfxElementKind,
    public val brightness: Int?,
    public val glowArgb: Int?,
    public val billboard: DisplayBillboard,
    public val viewRange: Float?,
    public val spawnTick: Int,
    /** Tick at which the entity is removed (`<= durationTicks`). */
    public val despawnTick: Int,
    public val initial: DisplayTransform,
    /** Ascending, at most one per tick, none at [spawnTick] (keyframe time `t` lands at `spawnTick + 1 + t`). */
    public val updates: List<VfxUpdate>,
    public val particles: List<VfxParticleEmission>,
    /** Spawn position as an offset from the plan origin (emitter particles start at their own point). */
    public val spawnOffset: Vec3 = Vec3.ZERO,
    /** Rig part standing in for the held item: lit by the weapon-rig shader rule (ADR-0275), see `DisplaySpec.weaponRigShading`. */
    public val weaponRigShading: Boolean = false,
    /** Quality lane of the authored element ([VfxQualityProfile]); rig and body parts are always `core`. */
    public val lane: VfxLane = VfxLane.CORE,
)

/**
 * At [tick]: set the transformation to [transform], interpolating over [interpolationTicks]
 * (`interpolation_delay = 0`), optionally swapping the content.
 */
public data class VfxUpdate(
    public val tick: Int,
    public val transform: DisplayTransform,
    public val interpolationTicks: Int,
    public val block: String? = null,
    public val text: String? = null,
    /** Quad colour/alpha or sprite opacity sent with this update. */
    public val style: VfxQuadStyle? = null,
    /**
     * Quad emitter particles only: move the ENTITY (teleport with `teleport_duration` = [interpolationTicks]) to this
     * offset from the plan origin. Translation in the display transformation would be billboard-relative, so
     * trajectories go through the entity position instead.
     */
    public val position: Vec3? = null,
    /** Teleport window for [position]; `null` = [interpolationTicks]. Lets a long transform window keep short position steps. */
    public val positionTicks: Int? = null,
    /**
     * `true`: leave the transformation (and its running interpolation window, which colour / alpha share on the
     * client) untouched; [transform] / [interpolationTicks] are ignored. Used for position-only and
     * text-swap-only updates of emitter particles.
     */
    public val keepTransform: Boolean = false,
    /** Swap the `item_model` of an item display (the performance weapon, ADR-0282). */
    public val itemModel: String? = null,
)

/**
 * Emit the particle at [tick], [offset] from the plan origin.
 *
 * [pathOffsets] (only for `along_path` elements) are the extra, evenly spaced points between the
 * previous emission position and [offset] (the last entry equals [offset]); the runtime emits at each.
 * [tangent] is the element's direction of travel (`along_tangent` velocity), [center] the formation
 * centre (`outward` velocity), [trailTarget] the plan-origin-relative `toward` point.
 */
public data class VfxParticleEmission(
    public val tick: Int,
    public val offset: Vec3,
    public val pathOffsets: List<Vec3> = emptyList(),
    public val tangent: Vec3? = null,
    public val center: Vec3? = null,
    public val trailTarget: Vec3? = null,
)
