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

/** One `freezes:` window: [ticks] frozen ticks at rig tick [at]. */
internal class RigFreezeWindow(val at: Int, val ticks: Int)

/** One lowered pose key: [rotate] is the absolute rotation vector of the key (a null `rotate` holds the previous one). */
internal class RigPoseKey(val tick: Int, val rotate: Vec3?, val ease: String)

/** The performance body sampled once per rig tick: no blend, a `step` key is a cut. */
internal class RigBodyTrack(private val animation: SkinRigAnimation, private val slim: Boolean, private val off: Boolean = false) {
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

    /** Carrying-arm frame (right arm; the left arm for an off-hand rig) at fractional rig tick [s]: lerp / slerp between ticks, held across a cut. */
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
        val arm = if (off) SkinRigTransforms.leftArm(frame, slim) else SkinRigTransforms.rightArm(frame, slim)
        return Landmark(arm.joint, Quaternion(arm.rotation.x, arm.rotation.y, arm.rotation.z, arm.rotation.w))
    }

    private fun pose(track: RigBoneTrack?, at: Double): BonePose =
        if (track == null) BonePose.REST else BonePose(
            rotation = track.rotation?.sample(at) ?: Vec3.ZERO,
            position = track.position?.sample(at) ?: Vec3.ZERO,
            scale = track.scale?.sample(at) ?: Vec3(1.0, 1.0, 1.0),
        )

    companion object {
        fun restArm(slim: Boolean, off: Boolean = false): Landmark = (if (off) SkinRigTransforms.leftArm(RigFrame.REST, slim) else SkinRigTransforms.rightArm(RigFrame.REST, slim)).let {
            Landmark(it.joint, Quaternion(it.rotation.x, it.rotation.y, it.rotation.z, it.rotation.w))
        }
    }
}

/**
 * What the client shows of a rig's swing: the rotation-vector pose keys are read at every rig tick (slerp between keys, the key's
 * ease on the segment ending there); hit-stop windows stop the rig clock (`respect_hit_stop`). A [mounted] rig rides the caster's arm.
 */
internal class RigMotion(
    swing: List<RigPoseKey>,
    tilt: Vec3,
    rest: Vec3,
    private val pivot: Vec3,
    private val mounted: Boolean,
    freezes: List<RigFreezeWindow>,
    respectHitStop: Boolean,
    private val body: RigBodyTrack?,
    slim: Boolean,
    /** The off-hand blade: rides the left arm, and the grip sits mirrored (x) across the arm. */
    private val off: Boolean = false,
) {
    private class Key(val t: Int, val rot: Quaternion, val ease: Easing)

    /** The rig clock starts one tick after the rig row. */
    val base: Int = 1
    private val keys: List<Key>
    private val tilt = Quaternion.fromRotationVector(tilt)
    private val rest = Quaternion.fromRotationVector(rest)
    private val stops: List<Pair<Int, Int>>

    /** The clock stand-stills as body-clip holds: the same windows [clock] applies, in unheld clock ticks. */
    val holds: List<com.astarworks.astera.core.domain.animation.skinrig.SkinRigHold> get() = stops.map { (at, ticks) -> com.astarworks.astera.core.domain.animation.skinrig.SkinRigHold(at, ticks) }
    private val restArm = RigBodyTrack.restArm(slim, off)

    init {
        var cur = Quaternion.IDENTITY
        keys = swing.map { k ->
            cur = k.rotate?.let { Quaternion.fromRotationVector(it) } ?: cur
            Key(k.tick, cur, Easing.fromKey(k.ease) ?: error("rig: unknown swing ease '${k.ease}'"))
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

    private fun poseKeyAt(u: Double): Quaternion {
        if (keys.isEmpty()) return Quaternion.IDENTITY
        val first = keys.first()
        if (u <= first.t) return first.rot
        val last = keys.last()
        if (u >= last.t) return last.rot
        val k = keys.indexOfFirst { it.t >= u }
        val a = keys[k - 1]
        val b = keys[k]
        return Quaternion.slerp(a.rot, b.rot, b.ease.apply((u - a.t) / (b.t - a.t)))
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

    /** Part orientation (tilt, pose keys, rest) at rig tick [s]. */
    fun rotationAt(s: Double): Quaternion = tilt * poseKeyAt(s) * rest

    private fun armAt(s: Double): RigBodyTrack.Landmark =
        if (!mounted) RigBodyTrack.Landmark(Vec3.ZERO, Quaternion.IDENTITY) else body?.armAt(s) ?: restArm

    /** Rig-frame orientation in the parent: the arm carries the pose. [bodyS] is the body clip's own rig tick. */
    fun frameRotationAt(s: Double, bodyS: Double): Quaternion = armAt(bodyS).rotation * rotationAt(s)

    /** Origin of the rig frame (the grip, or the pivot of an anchored rig) in the parent's axes. */
    fun originAt(bodyS: Double): Vec3 {
        if (!mounted) return pivot
        val arm = armAt(bodyS)
        val off = SkinRigTransforms.HELD_GRIP_OFFSET
        val gx = if (this.off) -off.x else off.x
        val o = arm.rotation.rotate(Vec3(gx + pivot.x, off.y + pivot.y, off.z + pivot.z))
        return Vec3(arm.position.x + o.x, arm.position.y + o.y, arm.position.z + o.z)
    }
}
