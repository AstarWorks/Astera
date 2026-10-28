package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.animation.skinrig.BonePose
import com.astarworks.astera.core.domain.animation.skinrig.RigBoneTrack
import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.RigQuat
import com.astarworks.astera.core.domain.animation.skinrig.RigTransforms
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigEase
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.op.RigFreeze
import com.astarworks.astera.core.domain.skillengine.op.RigSwingKey
import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** `linear`, `step`, or a v1 easing name (`in_quad`, `out_expo`, ...); null when unknown. */
internal fun rigEasing(name: String?): Easing? = if (name == null) Easing.LINEAR else Easing.fromKey(name)

private fun quat(q: RigQuat): Quaternion = Quaternion(q.x, q.y, q.z, q.w)

/** The performance body sampled once per rig tick (port of the v1 `VfxBodyTrack`): no blend, a `step` key is a cut. */
internal class RigBodyTrack(private val animation: SkinRigAnimation, private val slim: Boolean) {
    class Landmark(val position: Vec3, val rotation: Quaternion)
    class State(val transforms: RigTransforms, val arm: Landmark)

    private val cache = HashMap<Int, State>()
    private val snapTicks: Set<Int> = buildSet {
        fun scan(track: RigBoneTrack?) {
            listOfNotNull(track?.rotation, track?.position, track?.scale).forEach { ch ->
                ch.keys.filter { it.ease == SkinRigEase.STEP && it.tick > 0 }.forEach { add(it.tick) }
            }
        }
        scan(animation.root)
        animation.bones.values.forEach(::scan)
    }
    val lengthTicks: Int get() = animation.lengthTicks
    fun isSnap(tick: Int): Boolean = tick in snapTicks

    fun at(tick: Int): State = tick.coerceIn(0, animation.lengthTicks).let { t -> cache.getOrPut(t) { compute(t) } }

    /** Right-arm frame the client shows at fractional rig tick [s]: lerp / slerp between ticks, held across a cut. */
    fun armAt(s: Double): Landmark {
        val l = Math.floor(s).toInt()
        val u = s - l
        val a = at(l).arm
        if (u <= 1e-9 || isSnap(l + 1)) return a
        val b = at(l + 1).arm
        return Landmark(a.position + (b.position - a.position) * u, Quaternion.slerp(a.rotation, b.rotation, u))
    }

    private fun compute(tick: Int): State {
        val frame = RigFrame(
            root = pose(animation.root, tick.toDouble()),
            bones = SkinRigBone.entries.associateWith { pose(animation.bones[it], tick.toDouble()) },
            leftHandGrip = if (animation.twoHandGrip) 1.0 else 0.0,
        )
        val arm = SkinRigTransforms.rightArm(frame, slim)
        return State(SkinRigTransforms.compute(frame, slim), Landmark(arm.joint, quat(arm.rotation)))
    }

    private fun pose(track: RigBoneTrack?, at: Double): BonePose =
        if (track == null) BonePose.REST else BonePose(
            rotation = track.rotation?.sample(at) ?: Vec3.ZERO,
            position = track.position?.sample(at) ?: Vec3.ZERO,
            scale = track.scale?.sample(at) ?: Vec3(1.0, 1.0, 1.0),
        )

    companion object {
        fun restArm(slim: Boolean): Landmark = SkinRigTransforms.rightArm(RigFrame.REST, slim).let { Landmark(it.joint, quat(it.rotation)) }
    }
}

/**
 * What the client shows of a rig's swing (port of the v1 `VfxRigMotion`): rotation-vector wrist keys cut into chunks of at most
 * `sampleTicks` ticks, halved while a chunk turns more than 60 degrees; hit-stop windows stop the clock.
 */
internal class RigMotion(
    private val op: VisualOp.Rig,
    private val body: RigBodyTrack?,
    respectHitStop: Boolean,
    private val slim: Boolean,
) {
    private class Key(val t: Int, val rot: Quaternion, val ease: Easing, val sample: Boolean)
    private class Chunk(val start: Int, val end: Int, val from: Quaternion, val to: Quaternion)

    val base: Int = 1
    private val keys: List<Key>
    private val tilt = Quaternion.fromRotationVector(op.tilt)
    private val rest = Quaternion.fromRotationVector(op.rest)
    private val rotCache = HashMap<Int, Quaternion>()
    private val chunks: List<Chunk>
    private val stops: List<Pair<Int, Int>>

    init {
        var cur = Quaternion.IDENTITY
        keys = op.swing.map { k: RigSwingKey ->
            cur = k.rotate?.let { Quaternion.fromRotationVector(it) } ?: cur
            Key(k.tick, cur, rigEasing(k.ease) ?: error("rig '${op.id}': unknown swing ease '${k.ease}'"), k.sample)
        }
        val windows = if (respectHitStop) merge(op.freezes) else emptyList()
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
        val points = (listOf(0) + keys.filter { !it.sample }.map { it.t } + stops.map { it.first }.filter { it <= (keys.lastOrNull()?.t ?: 0) }).distinct().sorted()
        val out = ArrayList<Chunk>()
        for (k in 1 until points.size) {
            val a = points[k - 1]
            val b = points[k]
            val d = b - a
            val n = (d + op.sampleTicks - 1) / op.sampleTicks
            var prev = a
            for (j in 1..n) {
                val cut = a + (j.toDouble() * d / n).roundToInt()
                split(prev, cut, out)
                prev = cut
            }
        }
        chunks = out
    }

    private fun merge(freezes: List<RigFreeze>): List<RigFreeze> {
        val out = ArrayList<RigFreeze>()
        for (f in freezes.sortedBy { it.at }) {
            val last = out.lastOrNull()
            if (last != null && f.at <= last.at + last.ticks) {
                out[out.size - 1] = RigFreeze(last.at, maxOf(last.at + last.ticks, f.at + f.ticks) - last.at)
            } else {
                out += f
            }
        }
        return out
    }

    private fun wristAt(u: Int): Quaternion {
        if (keys.isEmpty()) return Quaternion.IDENTITY
        val first = keys.first()
        if (u <= first.t) return first.rot
        val last = keys.last()
        if (u >= last.t) return last.rot
        val k = keys.indexOfFirst { it.t >= u }
        val a = keys[k - 1]
        val b = keys[k]
        return Quaternion.slerp(a.rot, b.rot, b.ease.apply((u - a.t).toDouble() / (b.t - a.t)))
    }

    private fun exact(u: Int): Quaternion = rotCache.getOrPut(u) { tilt * wristAt(u) * rest }

    private fun turnDegrees(start: Int, end: Int): Double = Math.toDegrees(exact(start).angleTo(exact(end)))

    private fun split(start: Int, end: Int, out: MutableList<Chunk>) {
        if (end - start >= 2 && turnDegrees(start, end) > MAX_STEP_DEG) {
            val mid = (start + end) / 2
            split(start, mid, out)
            split(mid, end, out)
        } else {
            out += Chunk(start, end, exact(start), exact(end))
        }
    }

    /** Part updates of v1 `VfxRigPlanner.partPlan`: (effect tick, rig tick reached, interpolation ticks) per turning chunk. */
    fun partSteps(): List<Triple<Int, Int, Int>> =
        chunks.filter { it.from.angleTo(it.to) > 1e-9 }.map { Triple(effectTickStart(it.start), it.end, it.end - it.start) }

    fun isSwingStep(t: Int): Boolean = keys.any { it.t == t && it.ease == Easing.STEP }

    fun rotationAt(s: Double): Quaternion {
        if (chunks.isEmpty()) return exact(0)
        if (s <= chunks.first().start) return chunks.first().from
        if (s >= chunks.last().end) return chunks.last().to
        val c = chunks.first { s < it.end }
        return Quaternion.slerp(c.from, c.to, (s - c.start) / (c.end - c.start))
    }

    private val restArm = RigBodyTrack.restArm(slim)

    private fun armAt(s: Double): RigBodyTrack.Landmark =
        if (!op.hand) RigBodyTrack.Landmark(Vec3.ZERO, Quaternion.IDENTITY) else body?.armAt(s) ?: restArm

    /** Rig-frame orientation in the parent: the arm carries the wrist swing. */
    fun frameRotationAt(s: Double): Quaternion = armAt(s).rotation * rotationAt(s)

    /** Origin of the rig frame (the grip, or the pivot of an anchored rig) at rig tick [s], in the parent's axes. */
    fun originAt(s: Double): Vec3 {
        if (!op.hand) return op.pivot
        val arm = armAt(s)
        return arm.position + arm.rotation.rotate(SkinRigTransforms.HELD_GRIP_OFFSET + op.pivot)
    }

    private fun shiftStart(l: Int): Int = stops.filter { it.first <= l }.sumOf { it.second }
    private fun shiftEnd(l: Int): Int = stops.filter { it.first < l }.sumOf { it.second }
    fun effectTickStart(l: Int): Int = base + l + shiftStart(l)
    fun effectTickEnd(l: Int): Int = base + l + shiftEnd(l)

    companion object {
        const val MAX_STEP_DEG: Double = 60.0
    }
}

/** One scheduled send: rig tick [rigTick] goes out at effect tick [tick] with client interpolation [interpolation]. */
internal class RigStep(val rigTick: Int, val tick: Int, val interpolation: Int)

internal fun rigSchedule(motion: RigMotion, last: Int, despawn: Int, isCut: (Int) -> Boolean): List<RigStep> {
    val out = ArrayList<RigStep>()
    var lastTick = motion.base - 1
    for (l in 1..last) {
        val cut = isCut(l)
        var tick = if (cut) motion.effectTickEnd(l) else motion.effectTickStart(l - 1)
        var interpolation = if (cut) 0 else 1
        if (tick <= lastTick) {
            tick = motion.effectTickEnd(l)
            interpolation = 0
        }
        if (tick >= despawn) break
        out += RigStep(l, tick, interpolation)
        lastTick = tick
    }
    return out
}

/**
 * One running [VisualOp.Rig]: drives the rig frame node and the skin-bone nodes on the v1 performance clock, and writes
 * every display riding them only when its pose changed (pose to pose).
 */
internal class RigPerformer(
    val op: VisualOp.Rig,
    val startTick: Int,
    private val motion: RigMotion,
    private val bodyMotion: RigMotion,
    private val track: RigBodyTrack?,
    private val slim: Boolean,
) {
    private class Pose(val frame: Frame, val size: Vec3) {
        fun differs(o: Pose): Boolean =
            (frame.origin - o.frame.origin).lengthSquared() > EPS * EPS || frame.rotation.angleTo(o.frame.rotation) > EPS ||
                (size - o.size).lengthSquared() > EPS * EPS
    }

    private val bones = LinkedHashMap<SkinRigBone, VisualHandle>()
    private val last = HashMap<String, Pose>()
    private val schedule: Map<Int, RigStep>
    private val bodySchedule: Map<Int, RigStep>
    private val partSchedule: Map<Int, Pair<Int, Int>>
    private var partDue: Pair<Int, Int>? = null
    private var curRigTick: Int = 0
    private var rigStepAt: Int = 0
    private var bodyStepAt: Int = 0

    init {
        val lastRig = op.durationTicks - motion.base
        schedule = rigSchedule(motion, lastRig, op.durationTicks) { track?.isSnap(it) == true || motion.isSwingStep(it) }.associateBy { it.tick }
        partSchedule = motion.partSteps().filter { it.first < op.durationTicks }.associate { it.first to (it.second to it.third) }
        bodySchedule = if (bodyMotion === motion) schedule
        else rigSchedule(bodyMotion, lastRig, op.durationTicks) { track?.isSnap(it) == true }.associateBy { it.tick }
    }

    private fun boneId(bone: SkinRigBone) = "${op.id}_${bone.yamlName}"

    /** Creates the rig frame pose, the bone nodes and displays at rig tick 0. */
    fun start(rt: PresentationRuntime, s: PresentationRuntime.CastState, ctx: OpContext) {
        val tree = s.ctx.tree
        applyRig(s, 0)
        op.tip?.let { tree.definePoint(FramePoint(FrameId(op.frame), "tip", it, null)) }
        op.root?.let { tree.definePoint(FramePoint(FrameId(op.frame), "root", it, null)) }
        op.edge?.let { tree.definePoint(FramePoint(FrameId(op.frame), "edge", Vec3.ZERO, it)) }
        if (track == null) return
        for (bone in SkinRigBone.entries) {
            val node = FrameId(boneId(bone))
            tree.remove(node)
            tree.add(FrameNode(node, FrameKind.PART, FrameId(op.parent), Frame(Vec3.ZERO))).getOrNull() ?: continue
            val pose = bonePose(s, bone, 0) ?: continue
            last[boneId(bone)] = Pose(pose.frame, pose.size)
            bones[bone] = ctx.ports.visuals.spawnSkinBone(rt.info.owner(ctx.cast), bone, slim, false, pose)
        }
    }

    private fun bonePose(s: PresentationRuntime.CastState, bone: SkinRigBone, l: Int): VisualPose? {
        val t = track!!.at(l).transforms.bones.getValue(bone)
        val tree = s.ctx.tree
        tree.setLocal(FrameId(boneId(bone)), Frame(Vec3(t.translation.x, t.translation.y, t.translation.z), quat(t.rotation)))
        val world = tree.worldOf(FrameId(boneId(bone))).getOrNull() ?: return null
        return VisualPose(world, t.scale, DisplayBillboard.FIXED, anchorFeetOf(s))
    }

    private fun anchorFeetOf(s: PresentationRuntime.CastState): Vec3? = s.ctx.tree.worldOf(FrameId("caster")).getOrNull()?.origin

    private fun applyRig(s: PresentationRuntime.CastState, l: Int) {
        s.ctx.tree.setLocal(FrameId(op.frame), Frame(motion.originAt(l.toDouble()), motion.frameRotationAt(l.toDouble())))
    }

    /** Interpolation of the write the displays riding the rig frame get this tick (null: no step, nothing to write). */
    var driven: Int? = null
        private set

    /** Rig-relative effect tick at which the `item_model` key of rig tick [t] lands: spawn for 0, else the arrival tick of rig tick [t] (v1 VfxBodyPlanner.withModelSwaps). */
    fun modelTick(t: Int): Int = if (t == 0) 0 else motion.effectTickEnd(t)

    /** Advances the performance to effect tick [e]; returns false once it is over. */
    fun tick(s: PresentationRuntime.CastState, visuals: com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway, e: Int): Boolean {
        driven = null
        partDue = partSchedule[e]
        if (e >= op.durationTicks) {
            bones.values.forEach { visuals.remove(it) }
            bones.clear()
            return false
        }
        schedule[e]?.let { step ->
            applyRig(s, step.rigTick)
            curRigTick = step.rigTick
            driven = step.interpolation
        }
        if (track != null) {
            bodySchedule[e]?.let { step ->
                for ((bone, handle) in bones) {
                    val pose = bonePose(s, bone, step.rigTick) ?: continue
                    val next = Pose(pose.frame, pose.size)
                    val prev = last[boneId(bone)]
                    if (prev == null || next.differs(prev)) {
                        visuals.write(handle, pose, step.interpolation)
                        last[boneId(bone)] = next
                    }
                }
            }
        }
        return true
    }

    /**
     * Runs [f] with the rig frame at the end of the part chunk due this tick (v1 partPlan: parts ride chunk updates, not the
     * per-tick weapon schedule); [f] gets the interpolation. Null when no chunk starts this tick.
     */
    fun <T> atPartChunk(s: PresentationRuntime.CastState, f: (Int) -> T?): T? {
        val due = partDue ?: return null
        applyRig(s, due.first)
        try {
            return f(due.second)
        } finally {
            applyRig(s, curRigTick)
        }
    }

    /** True when [pose] of display [id] differs from what was last written for it; remembers it. */
    fun changed(id: String, pose: VisualPose): Boolean {
        val next = Pose(pose.frame, pose.size)
        val prev = last[id]
        if (prev != null && !next.differs(prev)) return false
        last[id] = next
        return true
    }

    fun end(s: PresentationRuntime.CastState, visuals: com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway) {
        bones.values.forEach { visuals.remove(it) }
        for (bone in bones.keys) s.ctx.tree.remove(FrameId(boneId(bone)))
        bones.clear()
    }

    private companion object {
        const val EPS = 1e-6
    }
}

/** `rig`: starts the skin-rig performance (ADR-0303 skin-rig section). */
internal class RigExecutor(private val rt: PresentationRuntime) : com.astarworks.astera.core.application.skillengine.runtime.OpExecutor<VisualOp.Rig> {
    override val opClass: kotlin.reflect.KClass<VisualOp.Rig> = VisualOp.Rig::class
    override fun execute(op: VisualOp.Rig, ctx: OpContext) {
        if (op.whenExpr?.let { ctx.values.bool(it) } == false) return
        val s = rt.state(ctx)
        if (s.ctx.tree.node(FrameId(op.frame)) == null) return ctx.host.fail(op.id, "rig: frame '${op.frame}' is not in the frame tree")
        if (s.ctx.tree.node(FrameId(op.parent)) == null) return ctx.host.fail(op.id, "rig: parent '${op.parent}' is not in the frame tree")
        val owner = rt.info.owner(ctx.cast)
        val clip = op.anim?.let { rt.casterAnim.clip(it) ?: return ctx.host.fail(op.id, "rig: no skin-rig clip '$it'") }
        if (clip != null && clip.lengthTicks != op.durationTicks) {
            return ctx.host.fail(op.id, "rig: clip '${op.anim}' is ${clip.lengthTicks} ticks but the rig lasts ${op.durationTicks}: a performance body must cover exactly its duration")
        }
        val slim = clip != null && rt.casterAnim.isSlim(owner)
        val track = clip?.let { RigBodyTrack(it, slim) }
        val motion = RigMotion(op, track, op.respectHitStop, slim)
        val bodyMotion = if (op.bodyRespectHitStop && !op.respectHitStop) RigMotion(op, track, true, slim) else motion
        val performer = RigPerformer(op, s.tick, motion, bodyMotion, track, slim)
        s.rigs.removeAll { it.op.id == op.id }
        s.rigs += performer
        performer.start(rt, s, ctx)
    }
}
