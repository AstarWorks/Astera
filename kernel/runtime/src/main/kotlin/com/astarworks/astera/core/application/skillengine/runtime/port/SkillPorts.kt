package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * Outbound ports of the skill engine (ADR-0301, R40). Every sound, particle, entity-query, visual, movement, item and world
 * call takes the [CastRef] of the cast it belongs to FIRST, so the adapter resolves the world from `cast.world` and a call can
 * never land in another world. All inputs are already evaluated numbers and
 * world-space poses; no frame or expression types cross the boundary. Paper implementations live in
 * `plugin/combat/.../adapter/paper/skillengine/`.
 */
/** The cast a port call belongs to (R40): the cast's id (for ledgers such as world reverts), its caster and the world it runs in. */
public data class CastRef(val castId: Long, val caster: UUID, val world: WorldId)

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
    /** `teleport_duration` of the write when it differs from the interpolation length (a followed effect, v1 VfxPlayer.kt:416); null = same. */
    val teleportTicks: Int? = null,
)

/** How a mesh item is drawn: pack model key set on the item, ItemDisplayContext, and whether the weapon-rig shader rule lights it. */
public data class MeshLook(
    val itemModel: String? = null,
    val mode: DisplayItemMode = DisplayItemMode.FIXED,
    val rigShading: Boolean = true,
)

/**
 * Who sees a display (v1 `Audience` / `VfxLane`): [audience] relative to the caster and the [target] (the victim in scope, or null),
 * [lane] dropped for viewers whose `vfx_intensity` does not admit it. Resolved by the adapter when the display is created.
 */
public data class DisplayView(
    val audience: com.astarworks.astera.core.domain.effect.Audience = com.astarworks.astera.core.domain.effect.Audience.ALL,
    val lane: com.astarworks.astera.core.domain.effect.VfxLane = com.astarworks.astera.core.domain.effect.VfxLane.CORE,
    val target: UUID? = null,
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
    public fun spawnMesh(cast: CastRef, item: String, part: String?, pose: VisualPose, brightness: Int? = null, look: MeshLook = MeshLook(), view: DisplayView = DisplayView(), glow: String? = null): VisualHandle

    /**
     * One bone of the caster's own skin rig (ADR-0303 skin-rig section): a skull display wearing the caster's skin, [ghost] = a
     * visual-only copy (no body hiding). [pose] is the world pose of the bone; it is written like a mesh. While the first live
     * bone of a caster exists the real body, offhand and armour are hidden and the pose is pinned.
     */
    public fun spawnSkinBone(cast: CastRef, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose): VisualHandle

    /**
     * A sprite quad; [pose].size.x / .y are its width and height in blocks. [tintHex] (`#rrggbb`) is multiplied into the
     * sprite from the first tick (null = the sprite's own colours), [seeThrough] is the vanilla text-display flag.
     * [brightness] null = full bright (15).
     */
    public fun spawnPlate(cast: CastRef, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int? = null, view: DisplayView = DisplayView(), glow: String? = null): VisualHandle

    /** A block display of vanilla block [block] (`gold_block`); [pose].size is its edge per axis in blocks, centred on the pose origin. */
    public fun spawnBlock(cast: CastRef, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean = false, view: DisplayView = DisplayView(), glow: String? = null): VisualHandle

    /**
     * A text display of the MiniMessage [text] ([pose].size = blocks per text unit) with a box of [backgroundArgbHex]
     * (`#aarrggbb`, `#00000000` = none); the text keeps its own colour and full opacity.
     */
    public fun spawnText(cast: CastRef, text: String, backgroundArgbHex: String, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView = DisplayView()): VisualHandle

    public fun spawnTrail(cast: CastRef, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle

    /** The ONE per-tick write of a mesh, plate, block or text: the whole [pose] reaches the client together and arrives after [interpolationTicks]. */
    public fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int)

    /** Append this tick's ribbon cross-section (root and tip positions); the adapter ages it out. */
    public fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3)

    public fun remove(handle: VisualHandle)

    /** Flipbook length and texture size of a sprite. An unknown sprite is an error, never a guessed size. */
    public fun sprite(sprite: String): SpriteInfo

    /** Ribbon look: alpha follows `(1 - age/ticks) ^ fadePower`; [style] is `soft`, `crisp` or `both`. Called once after [spawnTrail]. */
    public fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String)

    /**
     * Ribbon shape: [bands] (empty = one band in the colour [spawnTrail] got), leading [edge], dark [rim], the [stepDeg]
     * turn limit per quad and a reversed face when [doubleSided]. Called once after [spawnTrail], only for a trail that sets any of them.
     */
    public fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean)

    /** The width (same unit as [spawnTrail]'s `widthFrac`) of the points pushed from now on: a ribbon `grow prop: width` law (ADR-0303 3.4). */
    public fun setTrailWidth(handle: VisualHandle, widthFrac: Double)
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
    public fun emit(cast: CastRef, burst: ParticleBurst)
}

/** Positional sound: always at a world point. */
public interface ISkillSoundGateway {
    public fun play(cast: CastRef, sound: String, at: Vec3, category: String, volume: Double, pitch: Double)
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

    /**
     * One status `chance:` roll: true when the status lands. Drawn from the same random source as crits, one draw per
     * target and only for a chance below 1 (v1 `MechanicDispatcher.kt:627`: `if (chance < 1.0 && randomizer.nextDouble() > chance) continue`).
     */
    public fun rollChance(chance: Double): Boolean

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

    /**
     * Hold the look of [caster] (ADR-0303 10.6 `lock pitch/yaw`, founder Q3 default A): the pitch and/or yaw it has at this call
     * is held until the absolute server tick [untilTick] (`IScheduler.currentTick()` scale); the gateway re-sends the held
     * value whenever the client drifts more than 1 degree. A second call replaces the previous hold. No-op by default so
     * recording fakes need not implement it.
     */
    public fun lockLook(caster: UUID, pitch: Boolean, yaw: Boolean, untilTick: Int) {}

    /** Release the look hold of [caster] now (cast end, cancel, state exit, death, quit). Missing hold is a no-op. */
    public fun unlockLook(caster: UUID) {}

    /** `consume_status` (R24): removes every stack of [status] from [entity] (the stacks a `per_stack` read just counted). */
    public fun consumeStatus(cast: CastRef, entity: UUID, status: String)

    /**
     * `camera_shake` (R26): a decaying zero-sum series of view kicks for [viewer] over [ticks]. [from] is the world point the
     * shake comes from (`direction: from_hit`: the kicks lean away from it); null = a random axis per pulse.
     */
    public fun cameraShake(cast: CastRef, viewer: UUID, intensity: Double, ticks: Int, from: Vec3?)

    /** `stop_sound` (R26): stops the named sound for [viewer]. */
    public fun stopSound(cast: CastRef, viewer: UUID, sound: String)

    /** `body` (R41): plays the clip [anim] on the caster's own body (skin rig, else a pose clip) at [speed]. */
    public fun playBodyClip(cast: CastRef, caster: UUID, anim: String, speed: Double)

    /** The skin-rig clip [anim] (`rig anim:`): its length and arm track drive the rig's hand origin; null = no such skin-rig clip. */
    public fun bodyClip(anim: String): com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation? = null

    /** Whether [caster] wears a slim (Alex) skin: selects the arm of the skin rig. */
    public fun isSlimSkin(caster: UUID): Boolean = false

    /** Ends the [anim] clip on [caster] (the `body` window closed). A clip that is not playing is a no-op. */
    public fun stopBodyClip(cast: CastRef, caster: UUID, anim: String)

    /** `held_model` (ADR-0303 rev4.6): shows the item model [model] on the weapon [caster] holds. The item's own model is remembered on the first call. */
    public fun setHeldModel(cast: CastRef, caster: UUID, model: String)

    /** Ends a `held_model` window: the weapon [caster] holds shows its own model again. No swap active is a no-op. */
    public fun clearHeldModel(cast: CastRef, caster: UUID)

    /** Plays [target]'s hurt animation as if struck from [from] (R44): no damage, no push, no strength. */
    public fun hurt(cast: CastRef, target: UUID, from: Vec3)
}

/** Entity query with hitbox, used for the broad phase. */
public interface ISkillEntityQuery {
    /** Living, hittable entities within [radius] of [point], excluding [excluding]. */
    public fun nearby(cast: CastRef, point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate>

    /** Current feet position and body yaw of an entity (the victim / caster root frame). */
    public fun rootOf(cast: CastRef, entity: UUID): EntityRoot?

    /** Where blocks stop a moving body: first solid hit on segment [from]->[to], or null. */
    public fun raycastBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit?

    /** Health fraction 0..1 of a living entity (expression `hp_ratio`); an entity that is unknown or not living is an error. */
    public fun healthRatio(cast: CastRef, entity: UUID): Double

    /** [a] and [b] are on the same team of the match (v2 `PaperProjectileProbe`: both on one non-null team); false outside a match. */
    public fun isAlly(cast: CastRef, a: UUID, b: UUID): Boolean
}

public data class EntityRoot(val feet: Vec3, val yawDeg: Double, val pitchDeg: Double, val eyeHeight: Double, val height: Double)

public data class BlockHit(val point: Vec3, val normal: Vec3, val blockKey: String)

/** Caster / entity motion driven per tick by the kernel (R40, R9): the dash and reel drivers call these. */
public interface ISkillMovementGateway {
    /** Move [entity] to [pos] (and [yaw] / [pitch] when given) now. Dash and reel call this once per tick (v2 `DashStrikeDriver`). */
    public fun teleport(cast: CastRef, entity: UUID, pos: Vec3, yaw: Double?, pitch: Double?)

    /** Current velocity of [entity] in blocks per tick. */
    public fun velocity(cast: CastRef, entity: UUID): Vec3

    /** Add [v] to the velocity of [entity]. */
    public fun addVelocity(cast: CastRef, entity: UUID, v: Vec3)

    /** First solid block on the segment [from] -> [to] for a moving body of [entity], or null (stop-on-block probe). */
    public fun probeBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit?

    /** [entity] takes no damage for [ticks] ticks (`dash invulnerable`). */
    public fun invulnerable(cast: CastRef, entity: UUID, ticks: Int)

    /** Turn [entity] to face [dir] (`dash face_movement`). */
    public fun faceMovement(cast: CastRef, entity: UUID, dir: Vec3)
}

/** Items and cooldowns of the caster (the `consume_item` op, gated `use: skill:<id>`). */
public interface ISkillItemGateway {
    /** Remove up to [count] Astera items with id [item] from [holder]; true when all [count] were found (a short stack still loses what it had, as v2 did). */
    public fun consume(cast: CastRef, holder: UUID, item: String, count: Int): Boolean

    /** Start the cooldown of [skill] for the caster of [cast] for [ticks]. */
    public fun cooldown(cast: CastRef, skill: String, ticks: Int)

    public fun hasItem(cast: CastRef, holder: UUID, item: String): Boolean

    /**
     * A gated `use: skill:<id>` call (ADR-0303 section 9): apply the called skill's OWN cooldown ([cooldown]) and / or resource
     * cost ([cost]) to the caster of [cast]. The called rows play either way; an unpayable cost is the gateway's to report.
     */
    public fun applyGate(cast: CastRef, skill: String, cooldown: Boolean, cost: Boolean)
}

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
    val movement: ISkillMovementGateway,
    val items: ISkillItemGateway,
    val world: ISkillWorldGateway,
)
