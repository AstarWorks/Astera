package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import java.util.UUID

/**
 * Outbound ports of the skill engine (ADR-0301). All inputs are already evaluated numbers and
 * world-space poses; no frame or expression types cross the boundary. Paper implementations live in
 * `plugin/combat/.../adapter/paper/skillengine/`.
 */
@JvmInline
public value class VisualHandle(public val id: Long)

/**
 * Where a display stands this tick, in WORLD terms and BLOCKS (ADR-0303): the world [frame] of its tree node (origin plus
 * quaternion basis), its [size] in blocks along the frame's x / y / z (a plate: width, height; z ignored), the vanilla
 * [billboard] mode and, when the node hangs off the caster, the caster's [anchorFeet] the pose was computed against. The
 * adapter turns it into exactly the vanilla Display fields with one pure function; it does no other maths.
 */
public data class VisualPose(
    val frame: Frame,
    val size: Vec3,
    val billboard: DisplayBillboard = DisplayBillboard.FIXED,
    val anchorFeet: Vec3? = null,
)

/** A sprite's flipbook length and texture edge in pixels (its natural size is `px * 0.025` blocks). */
public data class SpriteInfo(val frames: Int, val px: Int)

/**
 * Display entities driven once per tick: one atomic write per display (location, the full transformation and a single
 * interpolation length). A display anchored to the caster rides the caster as a native passenger (ADR-0276), so it stays
 * glued to the hand when the caster moves (dash, knockback); every other display is teleported.
 */
public interface ISkillVisualGateway {
    /** An item model; [pose].size is the model scale in blocks per model unit. */
    public fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose): VisualHandle

    /** A sprite quad; [pose].size.x / .y are its width and height in blocks. */
    public fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, pose: VisualPose): VisualHandle

    /** A solid ribbon; [pose] is its centre, basis (x along the line) and size (length, thickness). */
    public fun spawnLine(castOwner: UUID, colorHex: String, pose: VisualPose): VisualHandle

    public fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle

    /** The ONE per-tick write of a mesh, plate or line: the whole [pose] reaches the client together and arrives after [interpolationTicks]. */
    public fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int)

    /** Append this tick's ribbon cross-section (root and tip positions); the adapter ages it out. */
    public fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3)

    public fun remove(handle: VisualHandle)

    /** Flipbook length and texture size of a sprite (1 frame, 32 px when unknown). */
    public fun sprite(sprite: String): SpriteInfo = SpriteInfo(1, 32)

    /** Show flipbook [frame] of a plate's sprite, multiplied by [tintHex] (`#rrggbb`) when not null. */
    public fun setPlateFrame(handle: VisualHandle, frame: Int, tintHex: String?) {}

    /** Ribbon look: alpha follows `(1 - age/ticks) ^ fadePower`; [style] is `soft`, `crisp` or `both`. Called once after [spawnTrail]. */
    public fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) {}

    /**
     * Ribbon shape: [bands] (empty = one band in the colour [spawnTrail] got), leading [edge], dark [rim], the [stepDeg]
     * turn limit per quad and a reversed face when [doubleSided]. Called once after [spawnTrail], only for a trail that sets any of them.
     */
    public fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) {}
}

/** One emit op as vanilla `spawnParticle` arguments plus the optional `dir` sugar (see [com.astarworks.astera.core.domain.skillengine.op.FxOp.Emit]). */
public data class ParticleBurst(
    val particle: String,
    val at: Vec3,
    /** null = unset: vanilla 1, or the custom definition's own count. */
    val count: Int?,
    val offset: Vec3,
    /** Vanilla `extra`. */
    val speed: Double,
    val colorHex: String?,
    val dataSize: Double?,
    /** `dir` sugar; null = plain vanilla spawn. */
    val dir: Vec3?,
    val spreadDeg: Double,
    val inheritVelocity: Vec3?,
    /** The caster's full look direction: orients a custom definition's shape when [dir] is null. */
    val look: Vec3,
    val seed: Long,
)

public interface ISkillParticleGateway {
    public fun emit(burst: ParticleBurst)
}

/** Positional sound: always at a world point. */
public interface ISkillSoundGateway {
    public fun play(sound: String, at: Vec3, volume: Double, pitch: Double)
}

public data class SkillDamage(val kind: String, val amount: Double)

/** Gameplay effects on entities. Damage numbers and status maths stay in combat; this is the seam. */
public interface ISkillGameplayGateway {
    public fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3)

    /** [damage] that may bypass the victim's invulnerability frames. Gateways with i-frames override this. */
    public fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean) {
        damage(caster, victim, components, hitPoint)
    }

    /**
     * [damage] attributed to the casting skill [skillId], so kill feeds, replays and tutorials see the real skill.
     * Gateways that record the source override this; the default forwards to the anonymous overloads.
     */
    public fun damage(skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean) {
        if (ignoreInvulnerability) damage(caster, victim, components, hitPoint, true) else damage(caster, victim, components, hitPoint)
    }

    /**
     * [damage] that may also skip the victim's armor / resist reduction. Gateways that model armor override this;
     * the default forwards to the overload without it.
     */
    public fun damage(
        skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3,
        ignoreInvulnerability: Boolean, ignoreArmor: Boolean,
    ) {
        damage(skillId, caster, victim, components, hitPoint, ignoreInvulnerability)
    }

    /** Current stacks of [statusId] on [entity] (0 when absent). */
    public fun statusStacks(entity: UUID, statusId: String): Int = 0
    public fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int)

    /** Remove one status (all stacks) from [victim]. Missing status is a no-op. */
    public fun removeStatus(victim: UUID, statusId: String)

    /** Remove statuses from [victim]; [negativeOnly] keeps beneficial ones. */
    public fun cleanse(victim: UUID, negativeOnly: Boolean)

    /** Set [victim] on fire (the existing burn runtime) for [durationTicks]. */
    public fun ignite(caster: UUID, victim: UUID, durationTicks: Int)

    /** Block skill casting of [victim] for [durationTicks]. */
    public fun silence(caster: UUID, victim: UUID, durationTicks: Int)

    /**
     * Attribute modifier on [victim]. [operation] is `add`, `multiply_base` or `multiply_total`;
     * [durationTicks] null means until removed (end of match / death).
     */
    public fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?)

    /** Add [amount] to a resource pool of [victim] (e.g. `mana`, `stamina`, `shield`), clamped to its max. */
    public fun restoreResource(victim: UUID, resource: String, amount: Double)
    public fun impulse(victim: UUID, vector: Vec3)

    /** Hit-stop: freeze the listed entities' time for [ticks]. */
    public fun freezeEntities(entities: List<UUID>, ticks: Int)
}

/** Entity query with hitbox, used for the broad phase. */
public interface ISkillEntityQuery {
    /** Living, hittable entities within [radius] of [point], excluding [excluding]. */
    public fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate>

    /** Current feet position and body yaw of an entity (the victim / caster root frame). */
    public fun rootOf(entity: UUID): EntityRoot?

    /** Where blocks stop a moving body: first solid hit on segment [from]->[to], or null. */
    public fun raycastBlock(from: Vec3, to: Vec3): BlockHit?

    /** Health fraction 0..1 of a living entity; 1 when unknown (expression `hp_ratio`). */
    public fun healthRatio(entity: UUID): Double = 1.0
}

public data class EntityRoot(val feet: Vec3, val yawDeg: Double, val pitchDeg: Double, val eyeHeight: Double, val height: Double)

public data class BlockHit(val point: Vec3, val normal: Vec3, val blockKey: String)

public interface ISkillVictimAnimGateway {
    public fun play(victim: UUID, anim: String, strength: Double, awayFrom: Vec3)
}

/** Bundle injected into the cast runner. */
public data class SkillRuntimePorts(
    val visuals: ISkillVisualGateway,
    val particles: ISkillParticleGateway,
    val sounds: ISkillSoundGateway,
    val gameplay: ISkillGameplayGateway,
    val entities: ISkillEntityQuery,
    val victimAnim: ISkillVictimAnimGateway,
)
