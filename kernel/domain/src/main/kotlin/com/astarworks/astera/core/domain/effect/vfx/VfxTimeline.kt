package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Curve
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.geometry.math.SeededRandom
import com.astarworks.astera.core.domain.geometry.math.distribute
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure planner: [VfxDefinition] + anchor orientation + cast seed -> [VfxPlan].
 *
 * Per slot the world transform is
 * `anchor . formationOffset . tilt . slotPoint/slotRotation . jitter . keyframe`:
 *
 *  - slot placement: `P = A * (offset + tilt * curve.point(t_i))`, slot rotation
 *    `Rs = A * tilt * align * jitterRotation` (`A` = anchor rotation);
 *  - keyframe state (translate / euler rotate / scale) is LOCAL to the slot, so a
 *    tangent-aligned element with `scale: [.., .., 6]` stretches along the curve;
 *  - the display transformation is `translation = P + Rs*T - L*(0.5*S)` (the last term
 *    centres block displays, whose origin is a corner, so they rotate/scale about their
 *    middle), `rotation = L = Rs * euler(R)`, `scale = S`.
 *
 * Group track ([VfxElement.groupKeyframes]): a whole-formation translate / rotate / uniform scale about
 * the anchor origin, applied before the slot geometry (`p' = T + Rg * (S * p)`, slot rotation `Rg * Rs`).
 * Its keyframe times join the element's as sampling breakpoints.
 *
 * Keyframes: absent fields carry over; the `ease` of a keyframe shapes the segment ending
 * at it; euler angles are interpolated component-wise (so a 720 degree roll is a double
 * spin) and each segment is sampled in chunks of at most `sampleTicks`, further halved
 * whenever one chunk would turn more than [MAX_STEP_ROTATION_DEG] (the client slerps the
 * shortest way, so a longer chunk would spin backwards or stall). Every chunk becomes one
 * [VfxUpdate] sent at the chunk start with `interpolationTicks` = chunk length. The keyframe
 * clock starts [ANIMATION_DELAY_TICKS] after the spawn, so no update lands on the spawn tick.
 */
public object VfxTimeline {

    /** Content-owned caster-space tables. Seeded jitter, cast scale and anchor rotation remain runtime inputs. */
    public class BakedVfx internal constructor(internal val elements: List<BakedElement>)

    internal class BakedElement(val element: VfxElement, duration: Int) {
        val frames = resolveKeyframes(element.keyframes)
        val group = resolveGroup(element.groupKeyframes)
        val breakpoints = (frames.map { it.t } + group.map { it.t }).distinct().sorted()
        val locals = if (frames.isEmpty()) emptyList() else (0..duration).map {
            oscillated(stateAt(frames, it), element.oscillate, it)
        }
        val localRotations = locals.map { Quaternion.fromEulerDegrees(it.euler.x, it.euler.y, it.euler.z) }
        val groups = (0..duration).map { groupAt(group, it) }
        val groupRotations = groups.map { Quaternion.fromEulerDegrees(it.euler.x, it.euler.y, it.euler.z) }
        val positions: List<Vec3>
        val rotations: List<Quaternion>
        init {
            val formation = element.formation
            val params = formation?.curve?.distribute(element.count, formation.closed) ?: List(element.count) { 0.0 }
            val tilt = formation?.tiltYawPitchRoll?.let { Quaternion.fromEulerDegrees(it.x, it.y, it.z) } ?: Quaternion.IDENTITY
            positions = params.map { t ->
                if (formation == null) Vec3.ZERO else formation.offset + tilt.rotate(formation.curve.point(t))
            }
            rotations = params.map { t ->
                if (formation == null) Quaternion.IDENTITY else when (formation.align) {
                    VfxAlign.TANGENT -> tilt * Quaternion.lookRotation(formation.curve.tangent(t))
                    VfxAlign.OUTWARD -> tilt * Quaternion.lookRotation(outward(formation.curve, formation.curve.point(t)))
                    VfxAlign.ANCHOR -> tilt
                    VfxAlign.NONE -> Quaternion.IDENTITY
                }
            }
        }
        fun localAt(u: Int): Local = locals.getOrNull(u) ?: oscillated(stateAt(frames, u), element.oscillate, u)
        fun localRotation(u: Int): Quaternion = localRotations.getOrNull(u) ?: localAt(u).euler.let {
            Quaternion.fromEulerDegrees(it.x, it.y, it.z)
        }
        fun groupAtTick(u: Int): GroupLocal = groups.getOrNull(u) ?: groupAt(group, u)
        fun groupRotation(u: Int): Quaternion = groupRotations.getOrNull(u) ?: groupAtTick(u).euler.let {
            Quaternion.fromEulerDegrees(it.x, it.y, it.z)
        }
    }

    public fun bake(def: VfxDefinition): BakedVfx = BakedVfx(def.elements.map { BakedElement(it, def.durationTicks) })


    /** A chunk turning more than this would be cut short by the client's shortest-path slerp. */
    public const val MAX_STEP_ROTATION_DEG: Double = 170.0

    /** Ticks between an entity's spawn and the start of its keyframe clock (`interpolation` needs a rendered tick first). */
    public const val ANIMATION_DELAY_TICKS: Int = 1

    /** Cap on interpolated `along_path` particle points per emission. */
    public const val MAX_PATH_POINTS: Int = 24

    /**
     * Plans [def] for an anchor oriented by [anchor]. [scale] multiplies every length
     * (formation, translations, display scales) - the `play_vfx` `scale` argument. [sprites] supplies the
     * frame counts that unroll `sprite` elements into flipbook text swaps (unknown sprite = 1 frame).
     * [freezes] are the `play_vfx` hit-stop windows; only rigs with `respect_hit_stop` honour them.
     */
    public fun plan(
        def: VfxDefinition,
        anchor: Frame,
        seed: Long,
        scale: Double = 1.0,
        sprites: SpriteFrames = SpriteFrames.NONE,
        freezes: List<VfxFreeze> = emptyList(),
        /** The resolved `body:` animation of a performance (ADR-0276); required when [VfxDefinition.body] is set. */
        body: VfxBodyContext? = null,
    ): VfxPlan {
        require((def.body == null) == (body == null)) {
            if (body == null) "effect '${def.id}' has body '${def.body}' but no body animation was resolved" else "effect '${def.id}' has no body but one was supplied"
        }
        val track = body?.let { VfxBodyTrack(it) }
        val slots = def.elements.flatMapIndexed { index, element ->
            planElement(def, element, index, anchor.rotation, seed, scale, sprites)
        } + def.rigs.flatMap { VfxRigPlanner.plan(def, it, anchor.rotation, scale, freezes, if (it.weapon != null) track else null) }
        return VfxPlan(
            anchor.origin, def.durationTicks, def.follow, slots.sortedBy { it.spawnTick }, def.followTeleportTicks, def.attachToAnchor, def.replaceGroup,
            performance = def.isPerformance, cutTicks = VfxBodyPlanner.cutTicks(slots),
        )
    }

    // ---- element ---------------------------------------------------------------

    private fun planElement(
        def: VfxDefinition,
        el: VfxElement,
        elementIndex: Int,
        anchorRot: Quaternion,
        seed: Long,
        scale: Double,
        sprites: SpriteFrames,
    ): List<VfxElementPlan> {
        if (el.emit != null) return VfxEmitterPlanner.plan(def, el, elementIndex, anchorRot, seed, scale, sprites)
        val formation = el.formation
        val baked = def.baked.elements[elementIndex]
        val absoluteEnd = el.endTick ?: def.durationTicks
        val out = ArrayList<VfxElementPlan>(el.count)

        for (slot in 0 until el.count) {
            val order = if (formation?.reverse == true) el.count - 1 - slot else slot
            val spawn = el.startTick + order * (formation?.staggerTicks ?: 0)
            if (spawn >= def.durationTicks) continue
            val despawn = min(def.durationTicks, el.slotLifetimeTicks?.let { spawn + it } ?: absoluteEnd)
            val life = despawn - spawn
            if (life <= 0) continue

            val geometry = slotGeometry(el, baked.positions[slot], baked.rotations[slot], anchorRot, scale, slotRandom(seed, elementIndex, slot))
            val ctx = SlotContext(el, baked, anchorRot, geometry, scale, life, def.sampleTicks)

            out += if (el.kind is VfxElementKind.Particle) {
                particleSlot(el, el.kind, formation, slot, spawn, despawn, ctx)
            } else {
                displaySlot(el, slot, spawn, despawn, ctx, sprites)
            }
        }
        return out
    }

    private fun slotRandom(seed: Long, elementIndex: Int, slot: Int): SeededRandom {
        val warmed = SeededRandom(seed xor (elementIndex.toLong() * 0x2545F4914F6CDD1DL) xor (slot.toLong() * 0x9E3779B97F4A7C15uL.toLong()))
        return SeededRandom(warmed.nextLong())
    }

    // ---- slot geometry ---------------------------------------------------------

    /** Where one slot sits and how it faces (before its keyframes); `position` is an offset from the anchor origin. */
    private class SlotGeometry(val position: Vec3, val rotation: Quaternion, val scaleFactor: Double)

    private fun slotGeometry(
        el: VfxElement,
        localPos: Vec3,
        slotRot: Quaternion,
        anchorRot: Quaternion,
        scale: Double,
        rng: SeededRandom,
    ): SlotGeometry {
        var jitterPos = Vec3.ZERO
        var jitterRot = Quaternion.IDENTITY
        var scaleFactor = 1.0
        val jitter = el.jitter
        if (jitter != null) {
            jitterPos = Vec3(
                rng.nextDouble(-jitter.position, jitter.position),
                rng.nextDouble(-jitter.position, jitter.position),
                rng.nextDouble(-jitter.position, jitter.position),
            )
            jitterRot = Quaternion.fromEulerDegrees(
                rng.nextDouble(-jitter.rotationDeg, jitter.rotationDeg),
                rng.nextDouble(-jitter.rotationDeg, jitter.rotationDeg),
                rng.nextDouble(-jitter.rotationDeg, jitter.rotationDeg),
            )
            scaleFactor = 1.0 + rng.nextDouble(-jitter.scale, jitter.scale)
        }
        return SlotGeometry(
            position = anchorRot.rotate((localPos + jitterPos) * scale),
            rotation = anchorRot * slotRot * jitterRot,
            scaleFactor = scaleFactor,
        )
    }

    /** Direction away from the formation centre: horizontal for planar curves, full 3D for spheres. */
    private fun outward(curve: Curve, point: Vec3): Vec3 {
        val flat = Vec3(point.x, 0.0, point.z)
        return if (!isSphere(curve) && flat.lengthSquared() > 1e-9) flat else point
    }

    private fun isSphere(curve: Curve): Boolean = when (curve) {
        is Curve.FibonacciSphere -> true
        is Curve.Transformed -> isSphere(curve.base)
        else -> false
    }

    // ---- keyframe state --------------------------------------------------------

    internal data class Local(val translate: Vec3, val euler: Vec3, val scale: Vec3)

    internal class Resolved(val t: Int, val local: Local, val ease: Easing, val block: String?, val text: String?)

    private val UNIT_SCALE = Vec3(1.0, 1.0, 1.0)

    internal data class GroupLocal(val translate: Vec3, val euler: Vec3, val scale: Double)

    internal class GroupResolved(val t: Int, val local: GroupLocal, val ease: Easing)

    private val GROUP_IDENTITY = GroupLocal(Vec3.ZERO, Vec3.ZERO, 1.0)

    private fun resolveGroup(frames: List<VfxGroupKeyframe>): List<GroupResolved> {
        var cur = GROUP_IDENTITY
        return frames.map { f ->
            cur = GroupLocal(f.translate ?: cur.translate, f.rotateEuler ?: cur.euler, f.scale ?: cur.scale)
            GroupResolved(f.t, cur, f.ease)
        }
    }

    private fun groupAt(frames: List<GroupResolved>, u: Int): GroupLocal {
        if (frames.isEmpty()) return GROUP_IDENTITY
        val first = frames.first()
        if (u <= first.t) return first.local
        val last = frames.last()
        if (u >= last.t) return last.local
        val k = frames.indexOfFirst { it.t >= u }
        val a = frames[k - 1]
        val b = frames[k]
        val f = b.ease.apply((u - a.t).toDouble() / (b.t - a.t))
        return GroupLocal(
            translate = lerp(a.local.translate, b.local.translate, f),
            euler = lerp(a.local.euler, b.local.euler, f),
            scale = a.local.scale + (b.local.scale - a.local.scale) * f,
        )
    }

    private fun resolveKeyframes(frames: List<VfxKeyframe>): List<Resolved> {
        var cur = Local(Vec3.ZERO, Vec3.ZERO, UNIT_SCALE)
        return frames.map { f ->
            cur = Local(f.translate ?: cur.translate, f.rotateEuler ?: cur.euler, f.scale ?: cur.scale)
            Resolved(f.t, cur, f.ease, f.block, f.text)
        }
    }

    /** Interpolated keyframe state at element-relative tick [u] (holds before the first / after the last keyframe). */
    private fun stateAt(frames: List<Resolved>, u: Int): Local {
        val first = frames.first()
        if (u <= first.t) return first.local
        val last = frames.last()
        if (u >= last.t) return last.local
        val k = frames.indexOfFirst { it.t >= u }
        val a = frames[k - 1]
        val b = frames[k]
        val f = b.ease.apply((u - a.t).toDouble() / (b.t - a.t))
        return Local(
            translate = lerp(a.local.translate, b.local.translate, f),
            euler = lerp(a.local.euler, b.local.euler, f),
            scale = lerp(a.local.scale, b.local.scale, f),
        )
    }

    private fun lerp(a: Vec3, b: Vec3, f: Double): Vec3 = a + (b - a) * f

    private fun oscillated(local: Local, osc: VfxOscillate?, u: Int): Local {
        if (osc == null) return local
        val d = osc.amplitude * cos(2.0 * PI * u / osc.periodTicks)
        val s = local.scale
        val t = local.translate
        return when (osc.axis) {
            VfxOscillateAxis.SCALE_X -> local.copy(scale = Vec3(s.x + d, s.y, s.z))
            VfxOscillateAxis.SCALE_Y -> local.copy(scale = Vec3(s.x, s.y + d, s.z))
            VfxOscillateAxis.SCALE_Z -> local.copy(scale = Vec3(s.x, s.y, s.z + d))
            VfxOscillateAxis.SCALE_XZ -> local.copy(scale = Vec3(s.x + d, s.y, s.z + d))
            VfxOscillateAxis.SCALE_XYZ -> local.copy(scale = Vec3(s.x + d, s.y + d, s.z + d))
            VfxOscillateAxis.TRANSLATE_X -> local.copy(translate = Vec3(t.x + d, t.y, t.z))
            VfxOscillateAxis.TRANSLATE_Y -> local.copy(translate = Vec3(t.x, t.y + d, t.z))
            VfxOscillateAxis.TRANSLATE_Z -> local.copy(translate = Vec3(t.x, t.y, t.z + d))
        }
    }

    private class SlotContext(
        val el: VfxElement,
        val baked: BakedElement,
        val anchorRot: Quaternion,
        val geometry: SlotGeometry,
        val scale: Double,
        val life: Int,
        val sampleTicks: Int,
    ) {
        val frames = baked.frames
        val group = baked.group
        fun localAt(u: Int): Local = baked.localAt(u)

        /** Sorted distinct element-relative ticks where the element or group track changes slope. */
        val breakpoints: List<Int> = baked.breakpoints

        /**
         * Group transform at [u]: (world rotation, extra world translation, uniform scale). The rotation
         * is the group euler conjugated into world space (`A . Rg . A^-1`) so it pivots in anchor axes.
         */
        private fun groupWorldAt(u: Int): Triple<Quaternion, Vec3, Double> {
            if (group.isEmpty()) return Triple(Quaternion.IDENTITY, Vec3.ZERO, 1.0)
            val g = baked.groupAtTick(u)
            val rg = baked.groupRotation(u)
            return Triple(anchorRot * rg * anchorRot.conjugate(), anchorRot.rotate(g.translate * scale), g.scale)
        }

        /** Slot position/rotation/scale-factor after the group track at [u]. */
        private fun placed(u: Int): Triple<Vec3, Quaternion, Double> {
            val (gr, gt, gs) = groupWorldAt(u)
            return Triple(gt + gr.rotate(geometry.position * gs), gr * geometry.rotation, gs)
        }

        /** Rotation delta (degrees) of the group track alone between two ticks (multi-turn aware). */
        fun groupTurnDegrees(start: Int, end: Int): Double {
            if (group.isEmpty()) return 0.0
            val a = baked.groupAtTick(start).euler
            val b = baked.groupAtTick(end).euler
            return maxOf(abs(b.x - a.x), abs(b.y - a.y), abs(b.z - a.z))
        }

        /** World-space (anchor-origin-relative) display transformation of the slot at element tick [u]. */
        fun transformAt(u: Int): DisplayTransform {
            val local = localAt(u)
            val (position, slotRotation, groupScale) = placed(u)
            val s = scale * geometry.scaleFactor * groupScale
            val sc = Vec3(local.scale.x * s, local.scale.y * s, local.scale.z * s)
            // CENTER already receives the camera rotation on the client. Applying the caster/formation
            // rotation again turns flashes edge-on when the caster faces east/west. Placement stays world-relative.
            val anchoredText = el.kind is VfxElementKind.Quad || el.kind is VfxElementKind.Sprite
            val facing = if (anchoredText && el.billboard == DisplayBillboard.CENTER) Quaternion.IDENTITY else slotRotation
            val rotation = facing * baked.localRotation(u)
            if (el.kind is VfxElementKind.Quad) return VfxQuad.transform(el.kind, rotation, sc)
            if (el.kind is VfxElementKind.Sprite) return DisplayTransform(Vec3.ZERO, rotation, sc)
            var translation = position + slotRotation.rotate(local.translate * scale * groupScale)
            if (el.kind is VfxElementKind.Block) translation -= rotation.rotate(sc * 0.5)
            return DisplayTransform(translation, rotation, sc)
        }

        fun centerAt(u: Int): Vec3 {
            val (position, slotRotation, groupScale) = placed(u)
            return position + slotRotation.rotate(localAt(u).translate * scale * groupScale)
        }
    }

    // ---- display slot ----------------------------------------------------------

    private fun displaySlot(
        el: VfxElement,
        slot: Int,
        spawn: Int,
        despawn: Int,
        ctx: SlotContext,
        sprites: SpriteFrames,
    ): VfxElementPlan {
        val first = ctx.frames.first()
        val sprite = el.kind as? VfxElementKind.Sprite
        val frameCount = sprite?.let { (sprites.frames(it.spriteId) ?: 1).coerceIn(1, SpriteGlyphs.MAX_FRAMES) } ?: 1
        val kind = when (val k = el.kind) {
            is VfxElementKind.Sprite -> VfxElementKind.Text(
                text = SpriteGlyphs.text(k.spriteId, 0, k.colorArgb),
                backgroundArgb = 0,
                shadow = false,
                seeThrough = k.seeThrough,
                textOpacity = spriteOpacity(el, 0.0),
            )
            is VfxElementKind.Block -> if (first.t == 0 && first.block != null) k.copy(blockKey = first.block) else k
            is VfxElementKind.Text -> if (first.t == 0 && first.text != null) k.copy(text = first.text) else k
            is VfxElementKind.Quad -> VfxQuad.trackAt(el, k, 0.0).let { (c, a) -> k.copy(colorRgb = c, alpha = a) }
            else -> el.kind
        }
        val anchoredText = el.kind is VfxElementKind.Quad || el.kind is VfxElementKind.Sprite
        return VfxElementPlan(
            elementId = el.id,
            slot = slot,
            kind = kind,
            brightness = el.brightness,
            glowArgb = el.glowArgb,
            billboard = el.billboard,
            viewRange = el.viewRange,
            spawnTick = spawn,
            despawnTick = despawn,
            initial = ctx.transformAt(0),
            updates = updatesFor(spawn, ctx, sprite, frameCount),
            particles = emptyList(),
            // Billboarded images sit at their world position; transformation translation rotates with the camera.
            spawnOffset = if (anchoredText) ctx.centerAt(0) else Vec3.ZERO,
        )
    }

    private fun updatesFor(spawn: Int, ctx: SlotContext, sprite: VfxElementKind.Sprite?, frameCount: Int): List<VfxUpdate> {
        // The client needs a tick with the entity before it can interpolate, so the keyframe
        // clock starts ANIMATION_DELAY_TICKS after the spawn: element-relative tick u happens at spawn + 1 + u.
        val base = spawn + ANIMATION_DELAY_TICKS
        val animLife = ctx.life - ANIMATION_DELAY_TICKS
        val steps = if (ctx.el.oscillate != null) unitSteps(animLife) else segmentSteps(ctx, ctx.sampleTicks)
        val byTick = LinkedHashMap<Int, VfxUpdate>()
        for (step in steps) {
            if (step.start >= animLife) continue
            byTick[base + step.start] = VfxUpdate(base + step.start, ctx.transformAt(step.end), step.end - step.start)
        }
        val swaps = ArrayList<Swap>()
        for (f in ctx.frames) {
            if (f.t < 1 || f.t >= animLife || (f.block == null && f.text == null)) continue
            swaps += Swap(f.t, f.block, f.text)
        }
        if (sprite != null) flipbookSwaps(sprite, frameCount, animLife, swaps)
        for (swap in swaps) {
            val tick = base + swap.u
            val existing = byTick[tick]
            byTick[tick] = existing?.copy(block = swap.block, text = swap.text)
                ?: swapInsideStep(swap, tick, steps, ctx)
        }
        val el = ctx.el
        if (el.kind is VfxElementKind.Quad || el.kind is VfxElementKind.Sprite) {
            val quad = el.kind as? VfxElementKind.Quad
            val styled = quad != null && VfxQuad.hasStyleTrack(el)
            return byTick.values.sortedBy { it.tick }.map { u ->
                val startU = u.tick - base
                val endU = startU + u.interpolationTicks
                u.copy(
                    position = ctx.centerAt(endU),
                    style = when {
                        styled && quad != null -> VfxQuad.trackAt(el, quad, startU + u.interpolationTicks / 2.0).let { (c, a) -> VfxQuad.style(quad, c, a) }
                        sprite != null && el.keyframes.any { it.alpha != null } -> VfxQuadStyle(0, spriteOpacity(el, startU + u.interpolationTicks / 2.0), null)
                        else -> null
                    },
                )
            }
        }
        return byTick.values.sortedBy { it.tick }
    }

    private fun spriteOpacity(el: VfxElement, u: Double): Int =
        (VfxQuad.trackAt(el, 0xFFFFFF, 1.0, u).second * 255).roundToInt().coerceIn(VfxQuad.MIN_TEXT_OPACITY, 255)

    private class Swap(val u: Int, val block: String?, val text: String?)

    /** Text swaps `frame k` at `u = k * frameTicks`: wrapping with [VfxElementKind.Sprite.loop], else stopping on the last frame. */
    private fun flipbookSwaps(sprite: VfxElementKind.Sprite, frameCount: Int, animLife: Int, out: MutableList<Swap>) {
        if (frameCount < 2) return
        var k = 1
        while (k * sprite.frameTicks < animLife) {
            if (!sprite.loop && k >= frameCount) break
            out += Swap(k * sprite.frameTicks, null, SpriteGlyphs.text(sprite.spriteId, k % frameCount, sprite.colorArgb))
            k++
        }
    }

    /**
     * A swap landing strictly inside an interpolation step must not cancel it: the update carries the step's
     * end transform and the remaining ticks, so the motion keeps flowing. Outside any step it is a plain swap.
     */
    private fun swapInsideStep(swap: Swap, tick: Int, steps: List<Step>, ctx: SlotContext): VfxUpdate {
        val inside = steps.firstOrNull { it.start < swap.u && swap.u < it.end }
        return if (inside != null) {
            VfxUpdate(tick, ctx.transformAt(inside.end), inside.end - swap.u, swap.block, swap.text)
        } else {
            VfxUpdate(tick, ctx.transformAt(swap.u), 0, swap.block, swap.text)
        }
    }

    private class Step(val start: Int, val end: Int)

    private fun unitSteps(life: Int): List<Step> = (0 until life).map { Step(it, it + 1) }

    private fun segmentSteps(ctx: SlotContext, sample: Int): List<Step> {
        val steps = ArrayList<Step>()
        for (k in 1 until ctx.breakpoints.size) {
            val a = ctx.breakpoints[k - 1]
            val b = ctx.breakpoints[k]
            val d = b - a
            val n = (d + sample - 1) / sample
            var prev = a
            for (j in 1..n) {
                val cut = a + Math.round(j.toDouble() * d / n).toInt()
                splitByRotation(ctx, prev, cut, steps)
                prev = cut
            }
        }
        return steps
    }

    private fun splitByRotation(ctx: SlotContext, start: Int, end: Int, out: MutableList<Step>) {
        if (end - start >= 2 && turnDegrees(ctx, start, end) > MAX_STEP_ROTATION_DEG) {
            val mid = (start + end) / 2
            splitByRotation(ctx, start, mid, out)
            splitByRotation(ctx, mid, end, out)
        } else {
            out += Step(start, end)
        }
    }

    /** How far the slot turns between two ticks: the larger of the euler delta and the quaternion angle. */
    private fun turnDegrees(ctx: SlotContext, start: Int, end: Int): Double {
        val a = ctx.localAt(start).euler
        val b = ctx.localAt(end).euler
        val euler = maxOf(abs(b.x - a.x), abs(b.y - a.y), abs(b.z - a.z)) + ctx.groupTurnDegrees(start, end)
        val qa = ctx.transformAt(start).rotation
        val qb = ctx.transformAt(end).rotation
        return max(euler, Math.toDegrees(qa.angleTo(qb)))
    }

    // ---- particle slot ---------------------------------------------------------

    /** Points spaced at most [step] apart from just after [from] up to and including [to]; empty when they coincide. */
    private fun pathBetween(from: Vec3, to: Vec3, step: Double): List<Vec3> {
        val distance = from.distance(to)
        if (distance < 1e-9) return emptyList()
        val n = ceil(distance / step).toInt().coerceIn(1, MAX_PATH_POINTS)
        return (1..n).map { i -> from + (to - from) * (i.toDouble() / n) }
    }

    private fun particleSlot(
        el: VfxElement,
        kind: VfxElementKind.Particle,
        formation: VfxFormation?,
        slot: Int,
        spawn: Int,
        despawn: Int,
        ctx: SlotContext,
    ): VfxElementPlan {
        val center = formation?.let { ctx.anchorRot.rotate(it.offset * ctx.scale) }
        val trailTarget = kind.trailTo?.let { ctx.anchorRot.rotate(it * ctx.scale) }
        val emissions = (0 until ctx.life step kind.everyTicks).map { u ->
            val at = ctx.centerAt(u)
            val next = if (u + 1 < ctx.life) ctx.centerAt(u + 1) - at else at - ctx.centerAt((u - 1).coerceAtLeast(0))
            val path = if (kind.alongPath && u > 0) {
                pathBetween(ctx.centerAt((u - kind.everyTicks).coerceAtLeast(0)), at, kind.stepBlocks)
            } else {
                emptyList()
            }
            VfxParticleEmission(
                tick = spawn + u,
                offset = at,
                pathOffsets = path,
                tangent = next.takeIf { it.lengthSquared() > 1e-12 }?.normalize(),
                center = center,
                trailTarget = trailTarget,
            )
        }
        return VfxElementPlan(
            elementId = el.id,
            slot = slot,
            kind = kind,
            brightness = null,
            glowArgb = null,
            billboard = el.billboard,
            viewRange = null,
            spawnTick = spawn,
            despawnTick = despawn,
            initial = DisplayTransform.IDENTITY,
            updates = emptyList(),
            particles = emissions,
        )
    }
}
