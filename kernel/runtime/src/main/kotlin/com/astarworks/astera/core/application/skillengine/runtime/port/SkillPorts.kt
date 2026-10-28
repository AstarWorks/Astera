package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.vfx.VfxLane
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import java.util.UUID

/**
 * Outbound ports of the skill engine (ADR-0301). The sound, particle and entity-query ports are bound to ONE world
 * (the casting entity's): the runner builds them per cast through [SkillRuntimePorts] factories, so a call can never
 * land in another world. All inputs are already evaluated numbers and
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
    /** Per-viewer quality class of the display; read at spawn (a non-core lane is shown to the viewers whose setting admits it). */
    val lane: VfxLane = VfxLane.CORE,
    /** `teleport_duration` of the write when it differs from the interpolation length (a followed effect, v1 VfxPlayer.kt:416); null = same. */
    val teleportTicks: Int? = null,
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
    public fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose, brightness: Int? = null): VisualHandle

    /**
     * One bone of the caster's own skin rig (ADR-0303 skin-rig section): a skull display wearing the caster's skin, [ghost] = a
     * visual-only copy (no body hiding). [pose] is the world pose of the bone; it is written like a mesh. While the first live
     * bone of a caster exists the real body, offhand and armour are hidden and the pose is pinned.
     */
    public fun spawnSkinBone(castOwner: UUID, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose): VisualHandle

    /** A sprite quad; [pose].size.x / .y are its width and height in blocks. [brightness] null = full bright (15). */
    public fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, pose: VisualPose, brightness: Int? = null): VisualHandle

    /** A block display of vanilla block [block] (`gold_block`); [pose].size is its edge per axis in blocks, centred on the pose origin. */
    public fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?): VisualHandle

    /**
     * A text-display quad: [text] null = a solid [colorHex] background at [alpha] ([pose].size.x / .y = width / height in blocks);
     * else the MiniMessage [text] over that background, [pose].size = blocks per text unit.
     */
    public fun spawnQuad(castOwner: UUID, colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?): VisualHandle

    /** A solid ribbon; [pose] is its centre, basis (x along the line) and size (length, thickness). */
    public fun spawnLine(castOwner: UUID, colorHex: String, pose: VisualPose): VisualHandle

    public fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle

    /** The ONE per-tick write of a mesh, plate or line: the whole [pose] reaches the client together and arrives after [interpolationTicks]. */
    public fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int)

    /** Append this tick's ribbon cross-section (root and tip positions); the adapter ages it out. */
    public fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3)

    public fun remove(handle: VisualHandle)

    /** Flipbook length and texture size of a sprite. An unknown sprite is an error, never a guessed size. */
    public fun sprite(sprite: String): SpriteInfo

    /** Show flipbook [frame] of a plate's sprite, multiplied by [tintHex] (`#rrggbb`) when not null. */
    public fun setPlateFrame(handle: VisualHandle, frame: Int, tintHex: String?)

    /** A plate's or quad's opacity 0..1 and, for a quad, its colour `#rrggbb` (null = unchanged); called only when a track key changes them. */
    public fun setLook(handle: VisualHandle, alpha: Double?, colorHex: String?) {}

    /** A block display switches to vanilla block [block]. */
    public fun setBlock(handle: VisualHandle, block: String) {}

    /** Ribbon look: alpha follows `(1 - age/ticks) ^ fadePower`; [style] is `soft`, `crisp` or `both`. Called once after [spawnTrail]. */
    public fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String)

    /**
     * Ribbon shape: [bands] (empty = one band in the colour [spawnTrail] got), leading [edge], dark [rim], the [stepDeg]
     * turn limit per quad and a reversed face when [doubleSided]. Called once after [spawnTrail], only for a trail that sets any of them.
     */
    public fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean)
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
    /**
     * Damage attributed to the casting skill [skillId] (kill feeds, replays and tutorials see the real skill). The ONE damage
     * entry point: [ignoreInvulnerability] bypasses the victim's i-frames, [ignoreArmor] skips armor / resist reduction.
     * There are no convenience overloads on purpose: a gateway or test fake implements this one member, so a flag added
     * later cannot fall through a delegating no-op.
     */
    public fun damage(
        skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3,
        ignoreInvulnerability: Boolean, ignoreArmor: Boolean,
    )

    /** Current stacks of [statusId] on [entity] (0 when the entity does not carry it). */
    public fun statusStacks(entity: UUID, statusId: String): Int
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

    /** Health fraction 0..1 of a living entity (expression `hp_ratio`); an entity that is unknown or not living is an error. */
    public fun healthRatio(entity: UUID): Double
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
