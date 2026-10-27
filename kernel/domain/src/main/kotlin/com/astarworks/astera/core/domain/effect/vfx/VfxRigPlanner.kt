package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What a client sees of a rig's swing: the swing keyframes (component-wise euler, eased) are cut into
 * chunks of at most [sampleTicks] ticks, halved while one chunk would turn more than
 * [MAX_STEP_DEG]; each chunk is ONE update (client interpolation: translation lerp + rotation slerp over
 * the chunk). [rotationAt] reproduces exactly that slerp, so trails are sampled from what is displayed.
 *
 * Granularity floor: the client interpolates per tick, so a chunk is never shorter than 1 tick. A swing
 * that turns more than [MAX_STEP_DEG] inside a single tick is therefore sent as-is (up to
 * [HARD_STEP_DEG], beyond which slerp would take the short way round) and fast swings need >= 1 update
 * per tick: 300 degrees in 3 ticks is 3 chunks of ~100 degrees, each linear in time.
 *
 * Hit-stop: the (merged) [freezes] stop the swing clock. A freeze at effect tick `at` ends the running
 * chunk there and the next one starts `ticks` later; [effectTickStart] / [effectTickEnd] map rig ticks
 * to effect ticks through them.
 */
public class VfxRigMotion(
    public val rig: VfxRig,
    public val anchorRot: Quaternion,
    public val scale: Double,
    freezes: List<VfxFreeze> = emptyList(),
    public val sampleTicks: Int = 2,
    /** The performance body the rig hangs from (`parent: right_hand`); `null` = the arm stays at rest. */
    public val body: VfxBodyTrack? = null,
) {
    /** One client interpolation: rotation (anchor-local) from [from] at rig tick [start] to [to] at [end]. */
    public class Chunk(public val start: Int, public val end: Int, public val from: Quaternion, public val to: Quaternion)

    /** Effect tick at which rig tick 0 lives (the keyframe clock starts one tick after the spawn). */
    public val base: Int = rig.startTick + VfxTimeline.ANIMATION_DELAY_TICKS

    private val windows: List<VfxFreeze> = if (rig.respectHitStop) merge(freezes) else emptyList()

    /** (rig tick the clock stops at, frozen ticks). */
    private val stops: List<Pair<Int, Int>>

    private val keys = rig.baked.keys

    public val chunks: List<Chunk>

    init {
        val stopList = ArrayList<Pair<Int, Int>>()
        var acc = 0
        for (w in windows) {
            val at = w.at - base - acc
            if (at >= 0) {
                stopList += at to w.ticks
                acc += w.ticks
            }
        }
        stops = stopList

        val points = (listOf(0) + keys.map { it.t } + stops.map { it.first }.filter { it <= (keys.lastOrNull()?.t ?: 0) })
            .distinct().sorted()
        val out = ArrayList<Chunk>()
        for (k in 1 until points.size) {
            val a = points[k - 1]
            val b = points[k]
            val d = b - a
            val n = (d + sampleTicks - 1) / sampleTicks
            var prev = a
            for (j in 1..n) {
                val cut = a + (j.toDouble() * d / n).roundToInt()
                split(prev, cut, out)
                prev = cut
            }
        }
        chunks = out
        chunks.forEach {
            val turn = turnDegrees(it.start, it.end)
            require(turn <= HARD_STEP_DEG || isSwingStep(it.end)) {
                "rig '${rig.id}' swing turns ${turn.roundToInt()} degrees in ${it.end - it.start} tick(s) at t=${it.start}: " +
                    "the client would slerp the short way. Lengthen the swing or use a softer ease (max $HARD_STEP_DEG degrees per tick)"
            }
        }
    }

    /** `true` when the swing key at rig tick [t] is a cut (`ease: step`): no slerp, no chunking, the client jumps. */
    public fun isSwingStep(t: Int): Boolean = keys.any { it.t == t && it.ease == com.astarworks.astera.core.domain.animation.Easing.STEP }

    private fun merge(freezes: List<VfxFreeze>): List<VfxFreeze> {
        val out = ArrayList<VfxFreeze>()
        for (f in freezes.sortedBy { it.at }) {
            val last = out.lastOrNull()
            if (last != null && f.at <= last.at + last.ticks) {
                out[out.size - 1] = VfxFreeze(last.at, maxOf(last.at + last.ticks, f.at + f.ticks) - last.at)
            } else {
                out += f
            }
        }
        return out
    }

    private fun split(start: Int, end: Int, out: MutableList<Chunk>) {
        if (end - start >= 2 && turnDegrees(start, end) > MAX_STEP_DEG) {
            val mid = (start + end) / 2
            split(start, mid, out)
            split(mid, end, out)
        } else {
            out += Chunk(start, end, exact(start), exact(end))
        }
    }

    private fun eulerAt(u: Int): Vec3 = rig.baked.eulerAt(u)

    /** Exact anchor-local rotations were sampled once at content load. */
    private fun exact(u: Int): Quaternion = rig.baked.rotationAt(u)

    /** Euler delta (max component) or quaternion angle, whichever is larger (multi-turn aware). */
    private fun turnDegrees(start: Int, end: Int): Double {
        val a = eulerAt(start)
        val b = eulerAt(end)
        val euler = maxOf(abs(b.x - a.x), abs(b.y - a.y), abs(b.z - a.z))
        return max(euler, Math.toDegrees(exact(start).angleTo(exact(end))))
    }

    /** Rotation (anchor-local) the client shows at fractional rig tick [s]. */
    public fun rotationAt(s: Double): Quaternion {
        if (chunks.isEmpty()) return exact(0)
        if (s <= chunks.first().start) return chunks.first().from
        if (s >= chunks.last().end) return chunks.last().to
        val c = chunks.first { s < it.end }
        return Quaternion.slerp(c.from, c.to, (s - c.start) / (c.end - c.start))
    }

    private val isHand: Boolean = rig.parent == VfxRigParent.RIGHT_HAND

    private val restArm: Landmark = SkinRigTransforms.rightArm(RigFrame.REST, body?.body?.slim ?: false)
        .let { Landmark(it.joint, VfxBodyTrack.quat(it.rotation)) }

    /** Anchor-local right-arm frame at rig tick [s] (identity for an anchor-parented rig). */
    public fun armAt(s: Double): Landmark =
        if (!isHand) Landmark(Vec3.ZERO, Quaternion.IDENTITY) else body?.armAt(s) ?: restArm

    /** Rig-frame orientation (anchor-local): the arm carries the wrist swing. */
    public fun frameRotationAt(s: Double): Quaternion = armAt(s).rotation * rotationAt(s)

    /** Anchor-local, scaled origin of the rig frame (the grip) at rig tick [s]. */
    public fun originAt(s: Double): Vec3 {
        if (!isHand) return rig.pivot * scale
        val arm = armAt(s)
        return (arm.position + arm.rotation.rotate(SkinRigTransforms.HELD_GRIP_OFFSET + rig.pivot)) * scale
    }

    /**
     * Orientation of the weapon item display (anchor-local): the rig frame times the constant grip pitch. The swing keys are
     * ABSOLUTE wrist rotations in the arm frame: all-zero keys on a body at rest are exactly what the player holds, and key 0
     * is free to be any pose (a coil from tick 0; the reference never starts from the held pose). `weapon_tip` / `weapon_edge`
     * are therefore expressed after the grip pitch: the model blade axis turned by `rotX(72)`.
     */
    public fun weaponRotationAt(s: Double): Quaternion = frameRotationAt(s) * weaponOffset

    private val weaponOffset: Quaternion =
        Quaternion.axisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(SkinRigTransforms.HELD_GRIP_PITCH))

    /** World-axes rotation (anchor applied) at rig tick [s]. */
    public fun worldRotationAt(s: Double): Quaternion = anchorRot * frameRotationAt(s)

    /** Anchor-origin-relative world position of the rig origin at rig tick [s]. */
    public fun originWorldAt(s: Double): Vec3 = anchorRot.rotate(originAt(s))

    /** Anchor-origin-relative position of the rig-local point [p] at rig tick [s]. */
    public fun pointAt(p: Vec3, s: Double): Vec3 = originWorldAt(s) + worldRotationAt(s).rotate(p * scale)

    /** Anchor-origin-relative position of the pivot (anchor-parented rigs; constant). */
    public val pivotWorld: Vec3 = anchorRot.rotate(rig.pivot * scale)

    private fun shiftStart(l: Int): Int = stops.filter { it.first <= l }.sumOf { it.second }

    private fun shiftEnd(l: Int): Int = stops.filter { it.first < l }.sumOf { it.second }

    /** Effect tick at which a chunk / sample STARTING at rig tick [l] is sent. */
    public fun effectTickStart(l: Int): Int = base + l + shiftStart(l)

    /** Effect tick at which the state of rig tick [l] has been reached (a chunk ENDING at [l] completes). */
    public fun effectTickEnd(l: Int): Int = base + l + shiftEnd(l)

    /** `true` while effect tick [e] lies inside a freeze window. */
    public fun isFrozen(e: Int): Boolean = windows.any { e >= it.at && e < it.at + it.ticks }

    public companion object {
        /** Soft cap on the rotation of one chunk; chunks are subdivided down to 1 tick to honour it. */
        public const val MAX_STEP_DEG: Double = 60.0

        /** Hard cap: beyond this the client's shortest-path slerp would turn the wrong way. */
        public const val HARD_STEP_DEG: Double = 170.0
    }
}

/**
 * Pure planner for [VfxRig]s: parts -> block-display slots (rigid: every part's display transformation is
 * `pivot frame . swing rotation . part offset`), trails / boards -> text-display background quads (soft)
 * and thin block slabs (crisp), emitters -> particle slots. Everything is an ordinary [VfxElementPlan].
 *
 * Soft quads are [VfxElementKind.Quad]s ([VfxQuad] holds the text-display calibration and the style
 * update path shared with display particles). The quad's local X is the blade (radial) direction and local
 * Y the direction of travel; the vanilla background is offset by half a pixel along X, which is why the
 * long dimension is kept on Y.
 */
public object VfxRigPlanner {

    /** Quads / slabs are this much longer than the interval they cover so neighbours overlap (no seams). */
    private const val OVERLAP: Double = 1.06

    private const val BAND_OUTER_WEIGHT: Double = 0.85

    /** Thickness of a crisp band slab. */
    private const val SLAB_THICKNESS: Double = 0.02

    private const val QUAD_BRIGHTNESS: Int = 15

    public fun plan(
        def: VfxDefinition,
        rig: VfxRig,
        anchorRot: Quaternion,
        scale: Double,
        freezes: List<VfxFreeze>,
        body: VfxBodyTrack? = null,
        /** ADR-0284: the track of every ghost body, by [VfxGhostBody.id]. */
        ghostTracks: Map<String, VfxBodyTrack> = emptyMap(),
    ): List<VfxElementPlan> {
        val motion = VfxRigMotion(rig, anchorRot, scale, freezes, def.sampleTicks, body)
        val out = ArrayList<VfxElementPlan>()
        if (rig.weapon != null) {
            out += VfxBodyPlanner.plan(def, rig, motion, body)
            def.ghostBodies.forEach { out += VfxBodyPlanner.planGhost(def, it, motion, ghostTracks.getValue(it.id)) }
        }
        rig.parts.forEach { out += partPlan(def, rig, it, motion) }
        rig.trails.forEach { out += trailPlans(def, it, motion) }
        rig.boards.forEach { out += boardPlans(def, it, motion) }
        rig.emitters.forEach { out += emitterPlan(def, it, motion) }
        require(out.size <= VfxDefinition.MAX_SLOTS) { "rig '${rig.id}' plans ${out.size} slots (max ${VfxDefinition.MAX_SLOTS})" }
        return out
    }

    // ---- parts -------------------------------------------------------------------

    private fun partPlan(def: VfxDefinition, rig: VfxRig, part: VfxRigPart, m: VfxRigMotion): VfxElementPlan {
        val spawn = rig.startTick
        val despawn = minOf(def.durationTicks, rig.endTick ?: def.durationTicks)
        val partRot = part.rotation
        val size = part.size * m.scale
        fun at(r: Quaternion): DisplayTransform {
            val rw = m.anchorRot * r
            val rot = rw * partRot
            val translation = m.pivotWorld + rw.rotate(part.offset * m.scale) - rot.rotate(size * 0.5)
            return DisplayTransform(translation, rot, size)
        }
        val updates = m.chunks
            .filter { it.from.angleTo(it.to) > 1e-9 }
            .map { VfxUpdate(m.effectTickStart(it.start), at(it.to), it.end - it.start) }
            .filter { it.tick < despawn }
        return VfxElementPlan(
            elementId = "${rig.id}.${part.id}",
            slot = 0,
            kind = VfxElementKind.Block(part.block),
            brightness = part.brightness,
            glowArgb = part.glowArgb,
            billboard = DisplayBillboard.FIXED,
            viewRange = null,
            spawnTick = spawn,
            despawnTick = despawn,
            initial = at(m.rotationAt(0.0)),
            updates = updates,
            particles = emptyList(),
            // A part with its own brightness is an effect prop; every other part is the held weapon (ADR-0275).
            weaponRigShading = part.brightness == null,
        )
    }

    // ---- trails ------------------------------------------------------------------

    /** Flat rectangle in 3D: [radial] along local X, [chord] along local Y, facing local Z. */
    private class Surface(val center: Vec3, val rotation: Quaternion, val radial: Double, val chord: Double)

    /** Rectangle approximating the trapezoid between radial segment `a0-a1` and its later position `b0-b1`. */
    private fun surface(a0: Vec3, a1: Vec3, b0: Vec3, b1: Vec3, outerWeight: Double = 0.5): Surface? {
        val center = (a0 + a1 + b0 + b1) * 0.25
        val rad = ((a1 - a0) + (b1 - b0)) * 0.5
        // The rectangle's width is the travel at `outerWeight` of the way out: > 0.5 favours the wide (outer) end,
        // trading a little overlap near the root for fewer notches between neighbouring quads.
        val chord = (b0 - a0) * (1.0 - outerWeight) + (b1 - a1) * outerWeight
        val radLen = rad.length()
        if (radLen < 1e-6) return null
        val ux = rad * (1.0 / radLen)
        val perp = chord - ux * chord.dot(ux)
        val chordLen = perp.length()
        if (chordLen < 1e-4) return null
        val cy = perp * (1.0 / chordLen)
        return Surface(center, Quaternion.lookRotation(ux.cross(cy), cy), radLen, chordLen)
    }

    private fun trailPlans(def: VfxDefinition, trail: VfxTrail, m: VfxRigMotion): List<VfxElementPlan> {
        val out = ArrayList<VfxElementPlan>()
        val bladeLen = (trail.to - trail.from).length() * m.scale
        fun at(f: Double, s: Double): Vec3 = m.pointAt(trail.from + (trail.to - trail.from) * f, s)

        val soft = trail.style != VfxTrailStyle.CRISP
        val crisp = trail.style != VfxTrailStyle.SOFT
        val intervals = sampleIntervals(m, trail.startT, trail.endT, trail.stepDeg)
        val counters = HashMap<String, Int>()
        fun slot(id: String): Int = counters.merge(id, 1, Int::plus)!! - 1

        fun addSoft(id: String, surf: Surface, argb: Int, toArgb: Int?, clock: FadeClock, glyph: String? = null) {
            out += softQuad(id, slot(id), surf, argb, toArgb, trail.fadePower, clock, glyph)
            if (trail.doubleSided) {
                val back = Surface(surf.center, surf.rotation * Quaternion.fromEulerDegrees(0.0, 180.0, 0.0), surf.radial, surf.chord)
                out += softQuad("$id.back", slot("$id.back"), back, argb, toArgb, trail.fadePower, clock, glyph)
            }
        }

        for ((s0, s1) in intervals) {
            val spawn = m.effectTickEnd(ceil(s1 - 1e-9).toInt()) + trail.lagTicks
            if (spawn >= def.durationTicks) continue
            val clock = fadeClock(def, m, spawn, trail.lifeTicks)

            trail.bands.forEachIndexed { index, band ->
                val surf = surface(at(band.from, s0), at(band.to, s0), at(band.from, s1), at(band.to, s1), if (band.glyph == null) BAND_OUTER_WEIGHT else 1.0)
                    ?: return@forEachIndexed
                if (soft) {
                    addSoft("${trail.id}.b$index", surf, band.argb, band.toArgb, clock, band.glyph)
                }
                if (crisp && band.block != null) {
                    out += slab("${trail.id}.k$index", slot("${trail.id}.k$index"), band.block, surf, surf.radial, SLAB_THICKNESS, trail.fadePower, clock, shrink = false)
                }
            }
            trail.edge?.let { edge ->
                val half = edge.width / (2.0 * bladeLen)
                surface(at(1.0 - half, s0), at(1.0 + half, s0), at(1.0 - half, s1), at(1.0 + half, s1))?.let { surf ->
                    out += slab("${trail.id}.edge", slot("${trail.id}.edge"), edge.block, surf, edge.width, edge.width, trail.fadePower, clock, shrink = true)
                }
            }
            if (soft) {
                trail.darkRim?.let { rim ->
                    val to = 1.0 + rim.width / bladeLen
                    surface(at(1.0, s0), at(to, s0), at(1.0, s1), at(to, s1))?.let { surf ->
                        addSoft("${trail.id}.rim", surf, rim.argb, null, clock)
                    }
                }
            }
        }
        return out
    }

    /**
     * Sample intervals `(s0, s1)` in rig ticks over `[startT, endT]`: one per tick at least, split further so the
     * blade turns at most [stepDeg] per interval.
     */
    public fun sampleIntervals(m: VfxRigMotion, startT: Int, endT: Int, stepDeg: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (k in startT until endT) {
            val angle = Math.toDegrees(m.frameRotationAt(k.toDouble()).angleTo(m.frameRotationAt(k + 1.0)))
            val n = ceil(angle / stepDeg - 1e-9).toInt().coerceIn(1, MAX_SUBSAMPLES)
            for (j in 0 until n) out += (k + j.toDouble() / n) to (k + (j + 1.0) / n)
        }
        return out
    }

    private const val MAX_SUBSAMPLES: Int = 12

    // ---- boards ------------------------------------------------------------------

    private fun boardPlans(def: VfxDefinition, board: VfxBoard, m: VfxRigMotion): List<VfxElementPlan> {
        val spawn = m.effectTickEnd(board.at)
        if (spawn >= def.durationTicks) return emptyList()
        fun mid(s: Double): Vec3 = m.pointAt((board.from + board.to) * 0.5, s)
        val travel = mid(board.at + 0.5) - mid(board.at - 0.5)
        if (travel.lengthSquared() < 1e-9) return emptyList()
        val back = travel.normalize() * -1.0
        val a0 = m.pointAt(board.from, board.at.toDouble())
        val a1 = m.pointAt(board.to, board.at.toDouble())
        val radius = (mid(board.at.toDouble()) - m.originWorldAt(board.at.toDouble())).length()
        val width = radius * Math.toRadians(board.arcDeg)
        val surf = surface(a0 + back * width, a1 + back * width, a0, a1) ?: return emptyList()
        val clock = fadeClock(def, m, spawn, board.ticks)
        return listOf(softQuad(board.id, 0, surf, board.argb, null, board.fadePower, clock))
    }

    // ---- emitters ----------------------------------------------------------------

    private fun emitterPlan(def: VfxDefinition, emitter: VfxRigEmitter, m: VfxRigMotion): VfxElementPlan {
        val emissions = ArrayList<VfxParticleEmission>()
        for (k in emitter.startT..emitter.endT) {
            val tick = m.effectTickEnd(k)
            if (tick >= def.durationTicks) break
            val here = m.pointAt(emitter.point, k.toDouble())
            val prev = if (k > emitter.startT) m.pointAt(emitter.point, k - 1.0) else here
            val path = if (k > emitter.startT) {
                val angle = Math.toDegrees(m.frameRotationAt(k - 1.0).angleTo(m.frameRotationAt(k.toDouble())))
                val n = maxOf(
                    ceil(angle / 12.0).toInt(),
                    ceil(prev.distance(here) / emitter.stepBlocks).toInt(),
                ).coerceIn(1, VfxTimeline.MAX_PATH_POINTS)
                (1..n).map { j -> m.pointAt(emitter.point, k - 1.0 + j.toDouble() / n) }
            } else {
                emptyList()
            }
            emissions += VfxParticleEmission(
                tick = tick,
                offset = here,
                pathOffsets = path,
                tangent = (here - prev).takeIf { it.lengthSquared() > 1e-12 }?.normalize(),
            )
        }
        val spawn = emissions.firstOrNull()?.tick ?: m.effectTickEnd(emitter.startT)
        return VfxElementPlan(
            elementId = "${m.rig.id}.${emitter.id}",
            slot = 0,
            kind = VfxElementKind.Particle(emitter.particleId, 1, true, emitter.stepBlocks),
            brightness = null,
            glowArgb = null,
            billboard = DisplayBillboard.FIXED,
            viewRange = null,
            spawnTick = spawn,
            despawnTick = def.durationTicks,
            initial = DisplayTransform.IDENTITY,
            updates = emptyList(),
            particles = emissions,
        )
    }

    // ---- fade --------------------------------------------------------------------

    /** Effect ticks (and their fade progress `1..life-1`) of one quad, plus its despawn tick; freeze-aware. */
    private class FadeClock(val spawn: Int, val life: Int, val steps: List<Pair<Int, Int>>, val despawn: Int)

    private fun fadeClock(def: VfxDefinition, m: VfxRigMotion, spawn: Int, life: Int): FadeClock {
        val steps = ArrayList<Pair<Int, Int>>()
        var progress = 0
        var e = spawn
        while (progress < life && e < def.durationTicks) {
            e++
            if (m.isFrozen(e)) continue
            progress++
            if (progress < life) steps += e to progress
        }
        return FadeClock(spawn, life, steps, minOf(e, def.durationTicks))
    }

    /** `(1 - progress/life)^power`, 1 at spawn, non-increasing. */
    private fun remaining(clock: FadeClock, progress: Int, power: Double): Double =
        (1.0 - progress.toDouble() / clock.life).coerceIn(0.0, 1.0).pow(power)

    private fun softQuad(
        elementId: String,
        slot: Int,
        surf: Surface,
        argb: Int,
        toArgb: Int?,
        fadePower: Double,
        clock: FadeClock,
        glyph: String? = null,
    ): VfxElementPlan {
        val rgb = argb and 0xFFFFFF
        val peak = (argb ushr 24) / 255.0
        val kind = VfxElementKind.Quad(colorRgb = rgb, alpha = peak, glyph = glyph)
        val transform = VfxQuad.transform(kind, surf.rotation, Vec3(surf.radial, surf.chord * (if (glyph == null) OVERLAP else 1.0), 1.0))
        val updates = clock.steps.map { (tick, progress) ->
            val colour = VfxQuad.lerpRgb(rgb, (toArgb ?: argb) and 0xFFFFFF, progress.toDouble() / clock.life)
            VfxUpdate(
                tick = tick,
                transform = transform,
                interpolationTicks = 0,
                style = VfxQuad.style(kind, colour, peak * remaining(clock, progress, fadePower)),
            )
        }
        return VfxElementPlan(
            elementId = elementId,
            slot = slot,
            kind = kind,
            brightness = QUAD_BRIGHTNESS,
            glowArgb = null,
            billboard = DisplayBillboard.FIXED,
            viewRange = null,
            spawnTick = clock.spawn,
            despawnTick = clock.despawn,
            initial = transform,
            updates = updates,
            particles = emptyList(),
            // the quad sits at its own entity position; VfxQuad.transform only centres the box on it
            spawnOffset = surf.center,
        )
    }

    /**
     * Opaque block slab on [surf]: [radial] x chord x [thickness]. Blocks have no alpha, so it fades by
     * shrinking: with [shrink] the radial + thickness axes thin out (a line that vanishes), otherwise the
     * chord shortens.
     */
    private fun slab(
        elementId: String,
        slot: Int,
        block: String,
        surf: Surface,
        radial: Double,
        thickness: Double,
        fadePower: Double,
        clock: FadeClock,
        shrink: Boolean,
    ): VfxElementPlan {
        fun at(f: Double): DisplayTransform {
            val k = f.coerceAtLeast(0.02)
            val size = if (shrink) {
                Vec3(radial * k, surf.chord * OVERLAP, thickness * k)
            } else {
                Vec3(radial, surf.chord * OVERLAP * k, thickness)
            }
            return DisplayTransform(surf.center - surf.rotation.rotate(size * 0.5), surf.rotation, size)
        }
        val updates = clock.steps.map { (tick, progress) ->
            VfxUpdate(tick, at(remaining(clock, progress, fadePower)), 0)
        }
        return VfxElementPlan(
            elementId = elementId,
            slot = slot,
            kind = VfxElementKind.Block(block),
            brightness = QUAD_BRIGHTNESS,
            glowArgb = null,
            billboard = DisplayBillboard.FIXED,
            viewRange = null,
            spawnTick = clock.spawn,
            despawnTick = clock.despawn,
            initial = at(1.0),
            updates = updates,
            particles = emptyList(),
        )
    }
}
