package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
/**
 * Weapon rig + 風切り (wind-cut) trail: a group of block displays that swings rigidly about a pivot
 * (the hand), and a fan of translucent surfaces generated from the swept blade line. Pure data;
 * [VfxRigPlanner] turns it into ordinary [VfxElementPlan]s, so the runtime and the preview need no
 * rig-specific code beyond recolouring text-display backgrounds.
 *
 * Frames: everything is expressed in the effect's anchor axes (x = left, y = up, z = forward). The
 * rig-local frame has its origin at the [pivot]; a part / blade point `p` sits at
 * `pivot + R(t) * p` with `R(t) = tilt * euler(swing(t)) * rest`.
 */
public data class VfxRig(
    public val id: String,
    /**
     * Anchor mode: the hand position relative to the anchor; the rig turns about it. With [parent] = [VfxRigParent.RIGHT_HAND]
     * it is an extra grip offset in the right-arm frame (default zero: the vanilla held grip).
     */
    public val pivot: Vec3,
    /** `(yaw, pitch, roll)` degrees applied OUTSIDE the swing: tilts the whole swing plane. */
    public val tiltYawPitchRoll: Vec3 = Vec3.ZERO,
    /** `(yaw, pitch, roll)` degrees of the rig at swing zero (e.g. pitch 90 turns a +Y blade to +Z). */
    public val restYawPitchRoll: Vec3 = Vec3.ZERO,
    public val startTick: Int = 0,
    /** ABSOLUTE tick the parts are removed at; `null` = until the effect ends. */
    public val endTick: Int? = null,
    public val parts: List<VfxRigPart>,
    /** Euler keyframes of the swing; empty = a static rig. */
    public val swing: List<VfxSwingKey> = emptyList(),
    public val trails: List<VfxTrail> = emptyList(),
    public val boards: List<VfxBoard> = emptyList(),
    public val emitters: List<VfxRigEmitter> = emptyList(),
    /** `true`: the freeze windows handed to [VfxTimeline.plan] (hit-stop) stop the swing and the fades. */
    public val respectHitStop: Boolean = false,
    /** What the rig frame is attached to (ADR-0276). */
    public val parent: VfxRigParent = VfxRigParent.ANCHOR,
    /**
     * The caster's real main-hand stack as ONE item display: `weapon(t) = hand(t) . grip . wrist(t)`. The `swing` keys are the
     * wrist, authored in the body-rest frame: key 0 is the vanilla held pose, later keys are rotations away from it.
     */
    public val weapon: VfxRigWeapon? = null,
) {
    internal val baked: BakedRig = BakedRig(this)

    init {
        require(id.isNotBlank()) { "VfxRig.id must not be blank" }
        require(parent == VfxRigParent.ANCHOR || parts.isEmpty()) { "rig '$id': parts cannot ride the right hand (use weapon: main_hand)" }
        require(weapon == null || parts.isEmpty()) { "rig '$id': weapon: main_hand replaces parts (one sword, ADR-0276)" }
        require(startTick >= 0) { "rig '$id' start_tick must be >= 0: $startTick" }
        require(endTick == null || endTick > startTick) { "rig '$id' end_tick ($endTick) must be > start_tick ($startTick)" }
        require(weapon != null || parts.isNotEmpty() || trails.isNotEmpty() || boards.isNotEmpty() || emitters.isNotEmpty()) {
            "rig '$id' needs a weapon, parts, trails, boards or emitters"
        }
        val ids = parts.map { it.id } + trails.map { it.id } + boards.map { it.id } + emitters.map { it.id }
        require(ids.toSet().size == ids.size) { "rig '$id' part / trail / board / emitter ids must be unique: $ids" }
        require(swing.firstOrNull()?.let { it.t >= 0 } ?: true) { "rig '$id' swing t must be >= 0" }
        swing.zipWithNext().forEach { (a, b) ->
            require(b.t > a.t) { "rig '$id' swing t must be strictly increasing (${a.t} then ${b.t})" }
        }
    }
}

/** What a [VfxRig] frame hangs from. */
public enum class VfxRigParent {
    /** The anchor (caster feet, cast-time yaw); the rig turns about [VfxRig.pivot]. */
    ANCHOR,

    /** The right arm of the performance body (needs `VfxDefinition.body`; without it the arm is at rest). */
    RIGHT_HAND,
}

/**
 * The weapon element of a rig. [tip], [root] and [edgeNormal] (rig-local, blocks) do not move the display; they describe the
 * blade for the plan dump (`skill-lint`) and the fx path tooling: [tip] defaults to the first trail's `to`, [root] is the end of
 * the blade (top of the guard).
 */
public data class VfxRigWeapon(
    public val tip: Vec3? = null,
    public val root: Vec3? = null,
    public val edgeNormal: Vec3 = Vec3(1.0, 0.0, 0.0),
    /**
     * Keyframed `item_model` override of the SAME display (ADR-0282): a step key holds until the next one, so a weapon can open
     * and close during a skill without a second weapon. Empty = the real stack's own model throughout.
     */
    public val models: List<VfxWeaponModelKey> = emptyList(),
) {
    init {
        models.zipWithNext().forEach { (a, b) ->
            require(b.t > a.t) { "weapon_model t must be strictly increasing (${a.t} then ${b.t})" }
        }
    }
}

/** Step key of [VfxRigWeapon.models]: from rig tick [t] the weapon display shows the item model [model] (`namespace:path`). */
public data class VfxWeaponModelKey(
    public val t: Int,
    public val model: String,
) {
    init {
        require(t >= 0) { "weapon_model t must be >= 0: $t" }
        require(MODEL_ID.matches(model)) { "weapon_model model must be a namespaced id (namespace:path): '$model'" }
    }

    private companion object {
        val MODEL_ID: Regex = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}

/**
 * One rigid block-display part. [offset] is the CENTRE of the part's box and [size] its box, both in the
 * rig-local frame (the planner shifts the display origin from the box corner for you); [rotateEuler]
 * additionally turns the part about its own centre (`(yaw, pitch, roll)`).
 */
public data class VfxRigPart(
    public val id: String,
    public val block: String,
    public val offset: Vec3,
    public val size: Vec3,
    public val rotateEuler: Vec3 = Vec3.ZERO,
    public val brightness: Int? = null,
    public val glowArgb: Int? = null,
) {
    public val rotation: Quaternion =
        Quaternion.fromEulerDegrees(rotateEuler.x, rotateEuler.y, rotateEuler.z)

    init {
        require(id.isNotBlank()) { "VfxRigPart.id must not be blank" }
        require(block.isNotBlank()) { "rig part '$id' block must not be blank" }
        require(brightness == null || brightness in 0..15) { "rig part '$id' brightness must be 0..15: $brightness" }
    }
}

/**
 * Swing keyframe at rig-relative tick [t] (the rig's keyframe clock starts one tick after its spawn, like
 * every element). [rotateEuler] is `(yaw, pitch, roll)` degrees, interpolated component-wise (a 300 degree
 * yaw is one sweep); absent = carry over. [ease] shapes the segment ENDING at this keyframe.
 */
public data class VfxSwingKey(
    public val t: Int,
    public val rotateEuler: Vec3? = null,
    public val ease: Easing = Easing.LINEAR,
)

/** Which surface kinds a trail builds. */
public enum class VfxTrailStyle {
    /** Translucent text-display background quads (alpha fade). */
    SOFT,

    /** Thin opaque block slabs (crisp, fades by shrinking). */
    CRISP,

    /** Both: soft bands plus the crisp slab of every band that names a block. */
    BOTH,
}

/**
 * One radial band of the fan, [from]..[to] as fractions of the root -> tip segment (0 = root, 1 = tip).
 * [argb] is the colour AND peak alpha of a soft quad; [toArgb] (opaque part used) is the colour it has
 * faded to at the end of its life (`null` = keeps its colour). [block] is the crisp slab block.
 */
public data class VfxTrailBand(
    public val from: Double,
    public val to: Double,
    public val argb: Int,
    public val toArgb: Int? = null,
    public val block: String? = null,
    /** Optional 8px-calibrated glyph; uses outer-edge chord width instead of rectangular overlap. */
    public val glyph: String? = null,
) {
    init {
        require(from >= 0.0 && to > from) { "trail band needs 0 <= from < to: $from..$to" }
    }
}

/** Crisp, opaque leading-edge line along the tip path (block slab of [width] blocks). */
public data class VfxTrailEdge(
    public val block: String = "white_concrete",
    public val width: Double = 0.04,
) {
    init {
        require(width > 0.0) { "trail edge width must be > 0: $width" }
    }
}

/** Dark band just outside the tip ([width] blocks wide) so the fan still reads against bright skies. */
public data class VfxTrailRim(
    public val argb: Int,
    public val width: Double = 0.18,
) {
    init {
        require(width > 0.0) { "trail dark_rim width must be > 0: $width" }
    }
}

/**
 * 風切り: samples the rig-local segment [from] -> [to] (blade root -> tip) from rig tick [startT] to
 * [endT]; every pair of consecutive samples becomes one quad per [bands] entry (a fan of trapezoids
 * approximated by rectangles). One sample per tick at least, plus extra samples so no step turns more
 * than [stepDeg]. Each quad lives [lifeTicks] and fades with `(1 - age/life)^fadePower`; [lagTicks]
 * delays the whole fan behind the blade.
 */
public data class VfxTrail(
    public val id: String,
    public val from: Vec3,
    public val to: Vec3,
    public val startT: Int,
    public val endT: Int,
    public val lifeTicks: Int,
    public val lagTicks: Int = 0,
    public val style: VfxTrailStyle = VfxTrailStyle.SOFT,
    public val bands: List<VfxTrailBand> = DEFAULT_BANDS,
    public val edge: VfxTrailEdge? = null,
    public val darkRim: VfxTrailRim? = null,
    public val stepDeg: Double = 30.0,
    public val fadePower: Double = 1.5,
    /** Emit a reversed soft face as well; crisp slabs already have both sides. */
    public val doubleSided: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "VfxTrail.id must not be blank" }
        require(endT > startT && startT >= 0) { "trail '$id' needs 0 <= start_t < end_t: $startT..$endT" }
        require(lifeTicks >= 1) { "trail '$id' life_ticks must be >= 1: $lifeTicks" }
        require(lagTicks >= 0) { "trail '$id' lag_ticks must be >= 0: $lagTicks" }
        require(stepDeg in 5.0..90.0) { "trail '$id' step_deg must be 5..90: $stepDeg" }
        require(fadePower >= 0.0) { "trail '$id' fade_power must be >= 0: $fadePower" }
        require((to - from).lengthSquared() > 1e-9) { "trail '$id' from and to must differ" }
        require(bands.isNotEmpty()) { "trail '$id' needs at least one band" }
        if (style == VfxTrailStyle.CRISP) {
            require(bands.any { it.block != null } || edge != null) { "trail '$id' style crisp needs a band block or an edge" }
        }
    }

    public companion object {
        /** Inner side faint, tip side bright: the radial gradient approximated by three bands. */
        public val DEFAULT_BANDS: List<VfxTrailBand> = listOf(
            VfxTrailBand(0.0, 0.45, 0x30FFFFFF),
            VfxTrailBand(0.45, 0.8, 0x70FFFFFF),
            VfxTrailBand(0.8, 1.0, 0xC0FFFFFF.toInt()),
        )
    }
}

/**
 * 1-frame slash board: one quad along the swing plane shown for [ticks] ticks from rig tick [at]. It spans
 * the rig-local segment [from] -> [to] radially and [arcDeg] of arc BEHIND the blade tangentially.
 */
public data class VfxBoard(
    public val id: String,
    public val at: Int,
    public val ticks: Int,
    public val from: Vec3,
    public val to: Vec3,
    public val arcDeg: Double,
    public val argb: Int,
    /** 0 = constant colour until removed; > 0 fades like a trail quad. */
    public val fadePower: Double = 0.0,
) {
    init {
        require(id.isNotBlank()) { "VfxBoard.id must not be blank" }
        require(at >= 0) { "board '$id' at must be >= 0: $at" }
        require(ticks >= 1) { "board '$id' ticks must be >= 1: $ticks" }
        require(arcDeg > 0.0 && arcDeg <= 180.0) { "board '$id' arc_deg must be 0..180: $arcDeg" }
        require((to - from).lengthSquared() > 1e-9) { "board '$id' from and to must differ" }
        require(fadePower >= 0.0) { "board '$id' fade_power must be >= 0: $fadePower" }
    }
}

/** Particle emitted every tick at the rig-local [point] (e.g. the blade tip), also along the path travelled. */
public data class VfxRigEmitter(
    public val id: String,
    public val particleId: ParticleId,
    public val point: Vec3,
    public val startT: Int,
    public val endT: Int,
    public val stepBlocks: Double = 0.4,
) {
    init {
        require(id.isNotBlank()) { "VfxRigEmitter.id must not be blank" }
        require(endT > startT && startT >= 0) { "emitter '$id' needs 0 <= start_t < end_t: $startT..$endT" }
        require(stepBlocks > 0.0) { "emitter '$id' step_blocks must be > 0: $stepBlocks" }
    }
}

/**
 * Hit-stop window: effect ticks `[at, at + ticks)` are frozen for every rig with `respect_hit_stop: true`
 * (the `freeze` argument of `play_vfx`). `at` is effect-relative like every other tick of the definition.
 */
public data class VfxFreeze(
    public val at: Int,
    public val ticks: Int,
) {
    init {
        require(at >= 0) { "freeze at must be >= 0: $at" }
        require(ticks >= 1) { "freeze ticks must be >= 1: $ticks" }
    }
}
