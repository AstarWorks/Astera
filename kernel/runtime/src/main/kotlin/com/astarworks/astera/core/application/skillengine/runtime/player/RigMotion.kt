package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.animation.skinrig.BonePose
import com.astarworks.astera.core.domain.animation.skinrig.RigBoneTrack
import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigEase
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import kotlin.math.roundToInt

/** One `freezes:` window: [ticks] frozen ticks at rig tick [at]. */
internal class RigFreezeWindow(val at: Int, val ticks: Int)

/** One lowered wrist key: [rot] is the absolute rotation vector of the key (a null `rotate` holds the previous one). */
internal class RigWristKey(val tick: Int, val rotate: Vec3?, val ease: String, val sample: Boolean)

/** The performance body sampled once per rig tick (v2 `RigBodyTrack` at 93cd534e4~1): no blend, a `step` key is a cut. */
internal class RigBodyTrack(private val animation: SkinRigAnimation, private val slim: Boolean) {
    class Landmark(val position: Vec3, val rotation: Quaternion)

    private val cache = HashMap<Int, Landmark>()
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

    /** Right-arm frame at fractional rig tick [s]: lerp / slerp between ticks, held across a cut. */
    fun armAt(s: Double): Landmark {
        val l = Math.floor(s).toInt()
        val u = s - l
        val a = at(l)
        if (u <= 1e-9 || (l + 1) in snapTicks) return a
        val b = at(l + 1)
        return Landmark(
            Vec3(a.position.x + (b.position.x - a.position.x) * u, a.position.y + (b.position.y - a.position.y) * u, a.position.z + (b.position.z - a.position.z) * u),
            Quaternion.slerp(a.rotation, b.rotation, u),
        )
    }

    private fun at(tick: Int): Landmark = tick.coerceIn(0, animation.lengthTicks).let { t -> cache.getOrPut(t) { compute(t) } }

    private fun compute(tick: Int): Landmark {
        val frame = RigFrame(
            root = pose(animation.root, tick.toDouble()),
            bones = SkinRigBone.entries.associateWith { pose(animation.bones[it], tick.toDouble()) },
            leftHandGrip = if (animation.twoHandGrip) 1.0 else 0.0,
        )
        val arm = SkinRigTransforms.rightArm(frame, slim)
        return Landmark(arm.joint, Quaternion(arm.rotation.x, arm.rotation.y, arm.rotation.z, arm.rotation.w))
    }

    private fun pose(track: RigBoneTrack?, at: Double): BonePose =
        if (track == null) BonePose.REST else BonePose(
            rotation = track.rotation?.sample(at) ?: Vec3.ZERO,
            position = track.position?.sample(at) ?: Vec3.ZERO,
            scale = track.scale?.sample(at) ?: Vec3(1.0, 1.0, 1.0),
        )

    companion object {
        fun restArm(slim: Boolean): Landmark = SkinRigTransforms.rightArm(RigFrame.REST, slim).let {
            Landmark(it.joint, Quaternion(it.rotation.x, it.rotation.y, it.rotation.z, it.rotation.w))
        }
    }
}

/**
 * What the client shows of a rig's swing (v2 `RigMotion` at 93cd534e4~1, a port of the v1 `VfxRigMotion`): rotation-vector wrist
 * keys cut into chunks of at most [sampleTicks] ticks, halved while a chunk turns more than 60 degrees; hit-stop windows stop the
 * rig clock (`respect_hit_stop`).
 */
internal class RigMotion(
    swing: List<RigWristKey>,
    tilt: Vec3,
    rest: Vec3,
    private val pivot: Vec3,
    private val hand: Boolean,
    private val sampleTicks: Int,
    freezes: List<RigFreezeWindow>,
    respectHitStop: Boolean,
    private val body: RigBodyTrack?,
    slim: Boolean,
) {
    private class Key(val t: Int, val rot: Quaternion, val ease: Easing, val sample: Boolean)
    private class Chunk(val start: Int, val end: Int, val from: Quaternion, val to: Quaternion)

    /** The rig clock starts one tick after the rig row (v2 `base`). */
    val base: Int = 1
    private val keys: List<Key>
    private val tilt = Quaternion.fromRotationVector(tilt)
    private val rest = Quaternion.fromRotationVector(rest)
    private val rotCache = HashMap<Int, Quaternion>()
    private val chunks: List<Chunk>
    private val stops: List<Pair<Int, Int>>
    private val restArm = RigBodyTrack.restArm(slim)

    init {
        var cur = Quaternion.IDENTITY
        keys = swing.map { k ->
            cur = k.rotate?.let { Quaternion.fromRotationVector(it) } ?: cur
            Key(k.tick, cur, Easing.fromKey(k.ease) ?: error("rig: unknown swing ease '${k.ease}'"), k.sample)
        }
        val windows = if (respectHitStop) merge(freezes) else emptyList()
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
            val n = (d + sampleTicks - 1) / sampleTicks
            var prev = a
            for (j in 1..n) {
                val cut = a + (j.toDouble() * d / n).roundToInt()
                split(prev, cut, out)
                prev = cut
            }
        }
        chunks = out
    }

    private fun merge(freezes: List<RigFreezeWindow>): List<RigFreezeWindow> {
        val out = ArrayList<RigFreezeWindow>()
        for (f in freezes.sortedBy { it.at }) {
            val last = out.lastOrNull()
            if (last != null && f.at <= last.at + last.ticks) {
                out[out.size - 1] = RigFreezeWindow(last.at, maxOf(last.at + last.ticks, f.at + f.ticks) - last.at)
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

    private fun split(start: Int, end: Int, out: MutableList<Chunk>) {
        if (end - start >= 2 && Math.toDegrees(exact(start).angleTo(exact(end))) > MAX_STEP_DEG) {
            val mid = (start + end) / 2
            split(start, mid, out)
            split(mid, end, out)
        } else {
            out += Chunk(start, end, exact(start), exact(end))
        }
    }

    /** The rig tick shown [elapsed] ticks after the rig row: the clock runs from [base] and stands still inside every hit-stop window. */
    fun clock(elapsed: Int): Double {
        var s = elapsed - base
        if (s <= 0) return 0.0
        var acc = 0
        for ((at, ticks) in stops) {
            val local = s - acc
            if (local <= at) break
            if (local <= at + ticks) return at.toDouble()
            acc += ticks
        }
        return (s - acc).toDouble()
    }

    /** Wrist orientation (tilt, swing, rest) at rig tick [s]. */
    fun rotationAt(s: Double): Quaternion {
        if (chunks.isEmpty()) return exact(0)
        if (s <= chunks.first().start) return chunks.first().from
        if (s >= chunks.last().end) return chunks.last().to
        val c = chunks.first { s < it.end }
        return Quaternion.slerp(c.from, c.to, (s - c.start) / (c.end - c.start))
    }

    private fun armAt(s: Double): RigBodyTrack.Landmark =
        if (!hand) RigBodyTrack.Landmark(Vec3.ZERO, Quaternion.IDENTITY) else body?.armAt(s) ?: restArm

    /** Rig-frame orientation in the parent: the arm carries the wrist swing. [bodyS] is the body clip's own rig tick. */
    fun frameRotationAt(s: Double, bodyS: Double): Quaternion = armAt(bodyS).rotation * rotationAt(s)

    /** Origin of the rig frame (the grip, or the pivot of an anchored rig) in the parent's axes. */
    fun originAt(bodyS: Double): Vec3 {
        if (!hand) return pivot
        val arm = armAt(bodyS)
        val off = SkinRigTransforms.HELD_GRIP_OFFSET
        val o = arm.rotation.rotate(Vec3(off.x + pivot.x, off.y + pivot.y, off.z + pivot.z))
        return Vec3(arm.position.x + o.x, arm.position.y + o.y, arm.position.z + o.z)
    }

    companion object {
        const val MAX_STEP_DEG: Double = 60.0
    }
}
