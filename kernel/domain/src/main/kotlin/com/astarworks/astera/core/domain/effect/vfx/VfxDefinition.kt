package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.geometry.math.Curve
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/**
 * Identifier of a [VfxDefinition] loaded from `content/vfx/<id>.yaml`
 * (lower-kebab-case, same convention as every other content id).
 */
@JvmInline
public value class VfxId(public val value: String) {
    init {
        require(value.matches(KEBAB)) { "VfxId must be lower-kebab: $value" }
    }

    override fun toString(): String = value

    public companion object {
        private val KEBAB = Regex("^[a-z][a-z0-9-]*$")
    }
}

/** How the effect's anchor frame is oriented when it is played. */
public enum class VfxOrient {
    /** Caster yaw + pitch. */
    LOOK,

    /** Caster yaw only (level). */
    YAW,

    /** No rotation: local axes are world axes. */
    WORLD,

    /** Caster -> anchor direction. */
    TO_TARGET,
    ;

    /** The frame is built from the caster's yaw, so an effect that rides the caster turns with it ([LOOK], [YAW]). */
    public val turnsWithYaw: Boolean get() = this == LOOK || this == YAW

    /**
     * Builds the anchor [Frame] at [origin]. [look] is the caster's look direction,
     * [toTarget] the caster -> anchor direction; a missing or degenerate vector
     * falls back to an unrotated frame ([TO_TARGET] falls back to [look] first).
     */
    public fun frame(origin: Vec3, look: Vec3?, toTarget: Vec3?): Frame {
        val dir = when (this) {
            WORLD -> null
            LOOK -> look
            YAW -> look?.let { Vec3(it.x, 0.0, it.z) }
            TO_TARGET -> toTarget?.takeIf { it.lengthSquared() > 1e-9 } ?: look
        }
        if (dir == null || dir.lengthSquared() < 1e-9) return Frame(origin, Quaternion.IDENTITY)
        return Frame.fromDirection(origin, dir)
    }
}

/**
 * Display choreography (Limbus-style multi-hit slashes, projections, text flashes):
 * a set of [VfxElement]s, each placed along a math curve and animated with keyframed,
 * eased, client-interpolated transforms. Pure data; [VfxTimeline] turns it into a plan.
 */
public data class VfxDefinition(
    public val id: VfxId,
    /** Whole-effect lifetime in ticks; every entity is removed at the end. */
    public val durationTicks: Int,
    public val orient: VfxOrient = VfxOrient.LOOK,
    /** `true`: the anchor position is re-resolved every tick (moves with an entity anchor). */
    public val follow: Boolean = false,
    /** Easing resolution: keyframe segments are split into chunks of at most this many ticks. */
    public val sampleTicks: Int = 2,
    public val elements: List<VfxElement>,
    /** Weapon rigs + 風切り trails ([VfxRig]); planned into ordinary slots by [VfxRigPlanner]. */
    public val rigs: List<VfxRig> = emptyList(),
    /** Optional anchor teleport smoothing override; 0 disables teleport smoothing. */
    public val followTeleportTicks: Int? = null,
    /** Opt-in native attachment for fixed displays following a player anchor. */
    public val attachToAnchor: Boolean = false,
    /** Replace an earlier following effect in this group on the same entity and world. */
    public val replaceGroup: String? = null,
    /** Authored target feet position in anchor-local coordinates; tracked slots translate by the live correction. */
    public val targetReference: Vec3? = null,
    /**
     * ADR-0276: the caster's skin-rig body animation (`content/animations/<id>.yaml`), sampled on the weapon rig's clock.
     * With a body, the rig's `weapon` is parented to the right hand and the whole effect is ONE performance.
     */
    public val body: CustomAnimationId? = null,
    /**
     * The effect's frame also turns with the caster's spine (body yaw of the caster's performance) while the performance plays:
     * for effects that belong to the torso / blade frame, e.g. a ring cast out of a back-turn twist. Needs `follow: true`.
     */
    public val followBodyYaw: Boolean = false,
    /**
     * The caster's look pitch (clamped to +-45 degrees) tilts the effect's frame about the caster's feet: a `yaw`-oriented effect
     * spawns tilted by the pitch at spawn. A performance (body / weapon rig) never pitches: it ignores this flag and stays upright.
     */
    public val followPitch: Boolean = false,
) {
    /** A performance owns the caster's hand, and with a body the whole body, for its duration. */
    public val isPerformance: Boolean get() = body != null || rigs.any { it.weapon != null }

    init {
        require(!followBodyYaw || (follow && body == null)) { "follow_body_yaw needs follow: true and no body of its own" }
        require(body == null || (follow && attachToAnchor && sampleTicks == 1)) {
            "body requires follow: true, attach_to_anchor: true and sample_ticks: 1 (the performance rides the caster)"
        }
        require(body == null || rigs.any { it.weapon != null && it.parent == VfxRigParent.RIGHT_HAND }) {
            "body needs a rig with weapon: main_hand and parent: right_hand"
        }
        require(rigs.none { it.weapon != null } || (follow && attachToAnchor)) {
            "a rig with weapon: main_hand needs follow: true and attach_to_anchor: true"
        }
        require(rigs.count { it.weapon != null } <= 1) { "at most one weapon rig per effect (one sword, ADR-0276)" }
        require(durationTicks in 1..MAX_DURATION_TICKS) { "VfxDefinition.durationTicks must be 1..$MAX_DURATION_TICKS: $durationTicks" }
        require(followTeleportTicks == null || followTeleportTicks in 0..59) { "followTeleportTicks must be 0..59" }
        require(replaceGroup == null || replaceGroup.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "replaceGroup must be lower-kebab, at most 64 characters" }
        require(sampleTicks >= 1) { "VfxDefinition.sampleTicks must be >= 1: $sampleTicks" }
        require(elements.isNotEmpty() || rigs.isNotEmpty()) { "VfxDefinition needs elements or rigs" }
        require(targetReference == null || (!follow && !attachToAnchor)) { "target tracking requires a fixed anchor without native attachment" }
        require(elements.none { it.targetLine } || sampleTicks == 1) { "target_line requires sample_ticks: 1" }
        require(elements.none { it.targetTrack.isNotEmpty() || it.targetLine } || targetReference != null) { "target tracks require target_reference" }
        val ids = elements.map { it.id } + rigs.map { it.id }
        require(ids.toSet().size == ids.size) { "VfxDefinition element ids must be unique: $ids" }
        val slots = elements.sumOf { it.slotCount }
        require(slots <= MAX_SLOTS) { "VfxDefinition has $slots element slots (max $MAX_SLOTS)" }
        elements.forEach {
            require(it.startTick < durationTicks) { "element '${it.id}' start_tick ${it.startTick} >= duration_ticks $durationTicks" }
        }
        rigs.forEach {
            require(it.startTick < durationTicks) { "rig '${it.id}' start_tick ${it.startTick} >= duration_ticks $durationTicks" }
        }
    }

    public val baked: VfxTimeline.BakedVfx = VfxTimeline.bake(this)

    public companion object {
        public const val MAX_DURATION_TICKS: Int = 200
        public const val MAX_SLOTS: Int = 256
    }
}

public data class VfxElement(
    public val id: String,
    public val kind: VfxElementKind,
    /** Fixed block + sky light 0..15 (emissive look). */
    public val brightness: Int? = null,
    public val glowArgb: Int? = null,
    public val billboard: DisplayBillboard = DisplayBillboard.FIXED,
    public val viewRange: Float? = null,
    public val startTick: Int = 0,
    /**
     * ABSOLUTE tick at which every slot is removed (staggered copies share it, so later slots live
     * shorter); `null` = until the effect ends. Overridden by [slotLifetimeTicks] when given.
     */
    public val endTick: Int? = null,
    /** Per-slot lifetime from its own spawn tick; keeps staggered copies equally long-lived. */
    public val slotLifetimeTicks: Int? = null,
    /** Formation copies. */
    public val count: Int = 1,
    public val formation: VfxFormation? = null,
    public val jitter: VfxJitter? = null,
    public val keyframes: List<VfxKeyframe>,
    public val oscillate: VfxOscillate? = null,
    /**
     * Whole-formation track (translate / rotate / scale of every slot of this element together),
     * applied about the anchor origin BEFORE the per-slot [keyframes]. Empty = static formation.
     */
    public val groupKeyframes: List<VfxGroupKeyframe> = emptyList(),
    /**
     * Particle emitter (display particles): spawns up to [VfxEmitter.count] quads whose trajectories the
     * planner precomputes. Needs kind `quad` or `sprite` (sprite: per-particle flipbook); [keyframes] are then optional (the first keyframe's `translate`
     * is the emitter origin) and [count] / [formation] / [oscillate] are unused.
     */
    public val emit: VfxEmitter? = null,
    /** Keys share the element keyframe clock: spawn + 1 + t. Model scale and rotation are preserved. */
    public val targetTrack: List<VfxTargetKey> = emptyList(),
    /** Stretch local +Z endpoint to the bound target; preserve the authored -Z endpoint. */
    public val targetLine: Boolean = false,
) {
    /** Display slots this element can plan (emitter particles count individually toward the budget). */
    public val slotCount: Int get() = emit?.count ?: count

    init {
        require(id.isNotBlank()) { "VfxElement.id must not be blank" }
        require(brightness == null || brightness in 0..15) { "element '$id' brightness must be 0..15: $brightness" }
        require(startTick >= 0) { "element '$id' start_tick must be >= 0: $startTick" }
        require(endTick == null || endTick > startTick) { "element '$id' end_tick ($endTick) must be > start_tick ($startTick)" }
        require(slotLifetimeTicks == null || slotLifetimeTicks >= 1) { "element '$id' slot_lifetime_ticks must be >= 1: $slotLifetimeTicks" }
        require(count in 1..MAX_COUNT) { "element '$id' count must be 1..$MAX_COUNT: $count" }
        require(keyframes.isNotEmpty() || emit != null) { "element '$id' needs at least one keyframe" }
        require(keyframes.isEmpty() || keyframes.first().t >= 0) { "element '$id' keyframe t must be >= 0" }
        require(emit == null || kind is VfxElementKind.Quad || kind is VfxElementKind.Sprite) { "element '$id': emit needs kind quad or sprite" }
        require(emit?.colorOverLife == null || kind is VfxElementKind.Quad) { "element '$id': emit.color_over_life needs kind quad" }
        require(targetTrack.isEmpty() || (emit == null && kind !is VfxElementKind.Particle)) { "target tracking supports non-emitting displays only" }
        require(!targetLine || (kind is VfxElementKind.Block && billboard == DisplayBillboard.FIXED && count == 1 && formation == null && emit == null && targetTrack.isEmpty())) { "target_line requires one fixed block without target_track or formation" }
        targetTrack.zipWithNext().forEach { (a, b) -> require(b.t > a.t) { "target track times must increase" } }
        keyframes.zipWithNext().forEach { (a, b) ->
            require(b.t > a.t) { "element '$id' keyframe t must be strictly increasing (${a.t} then ${b.t})" }
        }
        groupKeyframes.firstOrNull()?.let { require(it.t >= 0) { "element '$id' group_keyframes t must be >= 0" } }
        groupKeyframes.zipWithNext().forEach { (a, b) ->
            require(b.t > a.t) { "element '$id' group_keyframes t must be strictly increasing (${a.t} then ${b.t})" }
        }
        keyframes.forEach {
            require(it.block == null || kind is VfxElementKind.Block) { "element '$id': keyframe block swap needs kind block" }
            require(it.text == null || kind is VfxElementKind.Text) {
                if (kind is VfxElementKind.Sprite) "element '$id': sprite elements animate by flipbook, keyframe text swaps are not allowed"
                else "element '$id': keyframe text swap needs kind text"
            }
            require(it.color == null || kind is VfxElementKind.Quad) { "element '$id': keyframe color needs kind quad" }
            require(it.alpha == null || kind is VfxElementKind.Quad || kind is VfxElementKind.Sprite) { "element '$id': keyframe alpha needs kind quad or sprite" }
            require(it.alpha == null || it.alpha in 0.0..1.0) { "element '$id': keyframe alpha must be 0..1: ${it.alpha}" }
        }
    }

    public companion object {
        public const val MAX_COUNT: Int = 128
    }
}

public sealed interface VfxElementKind {
    /** `blockKey` is a vanilla block id with or without the `minecraft:` prefix. */
    public data class Block(public val blockKey: String) : VfxElementKind {
        init {
            require(blockKey.isNotBlank()) { "VfxElementKind.Block.blockKey must not be blank" }
        }
    }

    /** Vanilla material key (`netherite_sword`). */
    public data class Item(
        public val itemKey: String,
        public val displayMode: DisplayItemMode = DisplayItemMode.FIXED,
        /** Optional resource-pack item definition; material and gameplay item data remain vanilla. */
        public val itemModel: String? = null,
    ) : VfxElementKind {
        init {
            require(itemKey.isNotBlank()) { "VfxElementKind.Item.itemKey must not be blank" }
            require(itemModel == null || itemModel.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) { "item_model must be a namespaced resource key" }
            itemId // validates the key shape
        }

        /** [itemKey] as the lower-kebab [ItemId] the display gateway resolves (`netherite_sword` -> `netherite-sword`). */
        public val itemId: ItemId
            get() = ItemId(itemKey.removePrefix("minecraft:").lowercase().replace('_', '-'))
    }

    /** MiniMessage text (rendered by the adapter's text deserializer). */
    public data class Text(
        public val text: String,
        public val backgroundArgb: Int = 0,
        public val shadow: Boolean = false,
        public val seeThrough: Boolean = false,
        public val lineWidth: Int? = null,
        /** Initial glyph opacity, including resolved sprite keyframe alpha. */
        public val textOpacity: Int = 255,
    ) : VfxElementKind {
        init {
            require(textOpacity in 0..255) { "textOpacity must be in 0..255" }
            require(text.isNotBlank()) { "VfxElementKind.Text.text must not be blank" }
        }
    }

    /**
     * Custom particle sprite (ADR-0239): a flipbook of font glyphs shown on a TextDisplay. [VfxTimeline.plan]
     * resolves it into a [Text] kind plus timed text swaps, so runtimes need no sprite-specific display.
     * Frame `k` shows for [frameTicks] ticks; [loop] wraps after the last frame, otherwise it stays there.
     * [colorArgb] tints the glyph (`null` / white = the texture's own colours).
     */
    public data class Sprite(
        public val spriteId: SpriteId,
        public val frameTicks: Int = 1,
        public val loop: Boolean = false,
        public val colorArgb: Int? = null,
        public val seeThrough: Boolean = false,
    ) : VfxElementKind {
        init {
            require(frameTicks >= 1) { "VfxElementKind.Sprite.frameTicks must be >= 1: $frameTicks" }
        }
    }

    /**
     * Display particle: a coloured, translucent quad (TextDisplay) -- see [VfxQuad] for the calibration.
     * [colorRgb] is `0xRRGGBB`, [alpha] 0..1. With a [glyph] (`"✦"`, `"●"`) the shape is drawn as coloured text
     * (text colour + text_opacity) instead of a solid background rectangle.
     */
    public data class Quad(
        public val colorRgb: Int = 0xFFFFFF,
        public val alpha: Double = 1.0,
        public val glyph: String? = null,
        public val seeThrough: Boolean = false,
    ) : VfxElementKind {
        init {
            require(colorRgb in 0..0xFFFFFF) { "VfxElementKind.Quad.colorRgb must be 0xRRGGBB: $colorRgb" }
            require(alpha in 0.0..1.0) { "VfxElementKind.Quad.alpha must be 0..1: $alpha" }
            require(glyph == null || glyph.isNotBlank()) { "VfxElementKind.Quad.glyph must not be blank" }
        }
    }

    /** One bone of the caster's skin rig (ADR-0276); planned by [VfxBodyPlanner], never authored in `elements`. */
    public data class SkinBone(public val bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, public val slim: Boolean = false) : VfxElementKind

    /** The caster's real main-hand stack as the performance's one weapon display (ADR-0276). */
    public data object MainHandWeapon : VfxElementKind

    /**
     * No entity: emits a `content/particles` id at the element position every [everyTicks].
     *
     * [alongPath] also emits at [stepBlocks] spacing between the previous and the current emission
     * position (a continuous streak instead of one dot per tick). [trailTo] is an offset from the anchor
     * origin handed to the particle as its `toward` target (`trail` / `vibration` data).
     */
    public data class Particle(
        public val particleId: ParticleId,
        public val everyTicks: Int = 1,
        public val alongPath: Boolean = false,
        public val stepBlocks: Double = 0.4,
        public val trailTo: Vec3? = null,
    ) : VfxElementKind {
        init {
            require(everyTicks >= 1) { "VfxElementKind.Particle.everyTicks must be >= 1: $everyTicks" }
            require(stepBlocks > 0.0) { "VfxElementKind.Particle.stepBlocks must be > 0: $stepBlocks" }
        }
    }
}

public enum class VfxAlign {
    /** Local +Z follows the curve tangent. */
    TANGENT,

    /** Local +Z points away from the curve's origin. */
    OUTWARD,

    /** Keeps the formation (tilt) orientation: every slot faces like the tilted formation. */
    ANCHOR,

    /** Keeps the anchor orientation, ignoring tilt. */
    NONE,
}

/** Slots placed along [curve]; slot `i` sits at `curve.point(t_i)`. */
public data class VfxFormation(
    public val curve: Curve,
    /** `(yaw, pitch, roll)` degrees rotating the curve's plane. */
    public val tiltYawPitchRoll: Vec3 = Vec3.ZERO,
    /** Local offset of the whole formation (x = left, y = up, z = forward). */
    public val offset: Vec3 = Vec3.ZERO,
    public val align: VfxAlign = VfxAlign.NONE,
    /** Slot `i` starts `i * staggerTicks` after the element's `startTick`. */
    public val staggerTicks: Int = 0,
    /** Reverses the stagger order (the sweep runs against the curve direction). */
    public val reverse: Boolean = false,
) {
    init {
        require(staggerTicks >= 0) { "VfxFormation.staggerTicks must be >= 0: $staggerTicks" }
    }

    /** Whether slots wrap around (circle, polygon): the end point would duplicate the start. */
    public val closed: Boolean
        get() = curve.closed
}

/** Seeded per-slot randomness (deterministic for a given cast seed). */
public data class VfxJitter(
    /** Max positional offset per axis, blocks. */
    public val position: Double = 0.0,
    /** Max rotation per euler axis, degrees. */
    public val rotationDeg: Double = 0.0,
    /** Max relative scale change (0.1 = +-10%). */
    public val scale: Double = 0.0,
) {
    init {
        require(position >= 0.0 && rotationDeg >= 0.0 && scale >= 0.0) { "VfxJitter values must be >= 0" }
    }
}

/**
 * LOCAL transform of a slot at element-relative tick [t]. Absent fields carry over
 * from the previous keyframe (identity for the first one). [ease] shapes the segment
 * ENDING at this keyframe. [block] / [text] swap the displayed content at that tick
 * (displays have no alpha, so fades are done with a block ladder).
 */
public data class VfxKeyframe(
    public val t: Int,
    public val translate: Vec3? = null,
    /** `(yaw, pitch, roll)` degrees. Interpolated component-wise, so multi-turn spins work. */
    public val rotateEuler: Vec3? = null,
    public val scale: Vec3? = null,
    public val ease: Easing = Easing.LINEAR,
    public val block: String? = null,
    public val text: String? = null,
    /** Quad only: `0xRRGGBB`, interpolated in RGB with [ease]; sent as a style update per sample (the client does not tween colour). */
    public val color: Int? = null,
    /** Quad or sprite: 0..1, interpolated like [color]. */
    public val alpha: Double? = null,
)

/**
 * Whole-formation keyframe at element-relative tick [t]. Expressed in the anchor's local axes,
 * pivoting about the anchor origin: `p' = translate + rotate(scale * p)`. Absent fields carry over
 * (identity before the first); [ease] shapes the segment ending here. [scale] is uniform (it scales
 * the formation's positions and the display sizes alike).
 */
public data class VfxGroupKeyframe(
    public val t: Int,
    public val translate: Vec3? = null,
    /** `(yaw, pitch, roll)` degrees, interpolated component-wise (multi-turn spins work). */
    public val rotateEuler: Vec3? = null,
    public val scale: Double? = null,
    public val ease: Easing = Easing.LINEAR,
) {
    init {
        require(scale == null || scale > 0.0) { "VfxGroupKeyframe.scale must be > 0: $scale" }
    }
}

public enum class VfxOscillateAxis {
    SCALE_X, SCALE_Y, SCALE_Z,

    /** Both horizontal scale axes together (beam bloom). */
    SCALE_XZ,

    /** All three scale axes together. */
    SCALE_XYZ,
    TRANSLATE_X, TRANSLATE_Y, TRANSLATE_Z,
}

/** Per-tick flicker `amplitude * cos(2*PI * tick / periodTicks)` added on top of the keyframes. */
public data class VfxOscillate(
    public val axis: VfxOscillateAxis,
    public val amplitude: Double,
    public val periodTicks: Int,
) {
    init {
        require(periodTicks >= 1) { "VfxOscillate.periodTicks must be >= 1: $periodTicks" }
    }
}
