package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import java.util.UUID

/**
 * Outbound ports of the skill engine (ADR-0301). All inputs are already evaluated numbers and
 * world-space poses; no frame or expression types cross the boundary. Paper implementations live in
 * `plugin/combat/.../adapter/paper/skillengine/`.
 */
@JvmInline
public value class VisualHandle(public val id: Long)

/** Display entities teleported each tick with interpolation (F-6); no passengers. */
public interface ISkillVisualGateway {
    public fun spawnMesh(castOwner: UUID, item: String, part: String?, scale: Double, pose: Frame): VisualHandle

    /** [face] decides orientation; the adapter turns it into billboard/fixed rotation each update. */
    public fun spawnPlate(castOwner: UUID, sprite: String, face: PlateFace, size: Double, alpha: Double, pose: Frame): VisualHandle

    public fun spawnLine(castOwner: UUID, from: Vec3, to: Vec3, thickness: Double, colorHex: String, face: PlateFace): VisualHandle

    public fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle

    /** Move a mesh/plate. [interpolationTicks] is the display interpolation length. */
    public fun updatePose(handle: VisualHandle, pose: Frame, interpolationTicks: Int = 1)

    /** Uniform scale of a mesh (item scale) or plate (edge length in blocks). */
    public fun updateScale(handle: VisualHandle, scale: Double)

    public fun updateLine(handle: VisualHandle, from: Vec3, to: Vec3)

    /** Append this tick's ribbon cross-section (root and tip positions); the adapter ages it out. */
    public fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3)

    public fun remove(handle: VisualHandle)
}

public data class ParticleBurst(
    val particle: String,
    val at: Vec3,
    val count: Int,
    val dir: Vec3?,
    val spreadDeg: Double,
    val speed: Double,
    val inheritVelocity: Vec3?,
    val colorHex: String?,
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
