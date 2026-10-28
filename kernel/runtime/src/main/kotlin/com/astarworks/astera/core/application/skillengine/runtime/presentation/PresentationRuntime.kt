package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import kotlin.math.max

/**
 * Per-cast presentation object table and per-tick updater (design 3.7: "per-tick presentation
 * updates"). Presentation only READS the frame tree: it attaches objects to frames and points and
 * never moves a frame.
 *
 * The cast runner calls [tickCast] once per tick after gameplay/handlers, and [endCast] when the
 * cast ends or is cancelled.
 */
public class PresentationRuntime(
    internal val info: PresentationCastInfo,
    internal val casterAnim: ISkillCasterAnimGateway,
    internal val screen: ISkillScreenGateway,
) {
    private val casts = HashMap<CastId, CastState>()

    internal class TrackState(val op: VisualOp.Track, val startTick: Int)

    internal sealed class Live {
        abstract val opId: String
        abstract val handle: VisualHandle

        class Mesh(override val opId: String, override val handle: VisualHandle, val frame: String, val baseScale: Double) : Live()
        class Plate(
            override val opId: String,
            override val handle: VisualHandle,
            val parent: String,
            val face: PlateFace,
            val baseSize: Double,
        ) : Live()

        class Line(override val opId: String, override val handle: VisualHandle, val from: PointRef, val to: PointRef) : Live()
        class Trail(
            override val opId: String,
            override val handle: VisualHandle,
            val of: PointRef,
            val root: PointRef?,
            val ticks: Int,
            val untilEvent: String?,
        ) : Live() {
            var stoppedAt: Int = -1
        }
    }

    internal class SoundTask(val opId: String, val every: Int, val until: String?, var next: Int, val play: () -> Unit)

    internal class CastState(val ctx: OpContext) {
        var tick: Int = 0
        val live = LinkedHashMap<String, Live>()
        val sounds = LinkedHashMap<String, SoundTask>()
        val tracks = LinkedHashMap<String, TrackState>()
        /** Last world position an `emit` with `spacing` ran at, per op id. */
        val lastEmitAt = HashMap<String, Vec3>()
        val lastOrigin = HashMap<String, Vec3>()
        val velocity = HashMap<String, Vec3>()
        val watched = LinkedHashSet<String>()
    }

    internal fun state(ctx: OpContext): CastState = casts.getOrPut(ctx.cast) { CastState(ctx) }

    /** Frame origin velocity in blocks per tick; starts tracking the frame on first request. */
    internal fun velocityOf(s: CastState, frame: String): Vec3 {
        s.watched += frame
        return s.velocity[frame] ?: Vec3(0.0, 0.0, 0.0)
    }

    internal fun axes(s: CastState): AxisResolver = AxisResolver(s.ctx.tree) { velocityOf(s, it) }

    internal fun pointWorld(s: CastState, ref: PointRef): Vec3? {
        s.watched += ref.frame
        return s.ctx.tree.pointWorld(FrameId(ref.frame), ref.point).getOrNull()
    }

    internal fun frameWorld(s: CastState, frame: String): Frame? {
        s.watched += frame
        return s.ctx.tree.worldOf(FrameId(frame)).getOrNull()
    }

    internal fun register(s: CastState, live: Live) {
        removeIds(s, listOf(live.opId))
        s.live[live.opId] = live
    }

    /** Remove everything created by the listed op ids (objects, repeating sounds, tracks). */
    internal fun removeIds(s: CastState, ids: List<String>) {
        for (id in ids) {
            s.live.remove(id)?.let { s.ctx.ports.visuals.remove(it.handle) }
            s.sounds.remove(id)
            s.tracks.remove(id)
        }
    }

    public fun endCast(cast: CastId) {
        val s = casts.remove(cast) ?: return
        for (l in s.live.values) s.ctx.ports.visuals.remove(l.handle)
    }

    public fun tickCast(cast: CastId) {
        val s = casts[cast] ?: return
        s.tick++
        for (f in s.watched) {
            val o = s.ctx.tree.worldOf(FrameId(f)).getOrNull()?.origin ?: continue
            s.lastOrigin[f]?.let { prev -> s.velocity[f] = o - prev }
            s.lastOrigin[f] = o
        }
        val visuals = s.ctx.ports.visuals
        for (l in s.live.values.toList()) {
            when (l) {
                is Live.Mesh -> {
                    val base = frameWorld(s, l.frame) ?: continue
                    applyPose(s, l.opId, l.handle, base, l.frame, l.baseScale)
                }
                is Live.Plate -> {
                    val base = platePose(s, l.parent, l.face) ?: continue
                    applyPose(s, l.opId, l.handle, base, l.parent, l.baseSize)
                }
                is Live.Line -> {
                    val a = pointWorld(s, l.from) ?: continue
                    val b = pointWorld(s, l.to) ?: continue
                    visuals.updateLine(l.handle, a, b)
                }
                is Live.Trail -> tickTrail(s, l)
            }
        }
        for (t in s.sounds.values.toList()) {
            if (t.until != null && info.hasRaised(cast, t.until)) {
                s.sounds.remove(t.opId)
            } else if (s.tick >= t.next) {
                t.next = s.tick + t.every
                t.play()
            }
        }
    }

    private fun tickTrail(s: CastState, t: Live.Trail) {
        if (t.stoppedAt < 0 && t.untilEvent != null && info.hasRaised(s.ctx.cast, t.untilEvent)) t.stoppedAt = s.tick
        if (t.stoppedAt >= 0) {
            if (s.tick - t.stoppedAt >= t.ticks) {
                s.live.remove(t.opId)
                s.ctx.ports.visuals.remove(t.handle)
            }
            return
        }
        val tip = pointWorld(s, t.of) ?: return
        val root = t.root?.let { pointWorld(s, it) } ?: tip
        s.ctx.ports.visuals.pushTrailPoint(t.handle, root, tip)
    }

    /** World pose of a plate: parent pose for viewer faces (the adapter billboards), plane pose otherwise. */
    internal fun platePose(s: CastState, parent: String, face: PlateFace): Frame? = when (face) {
        is PlateFace.Plane -> axes(s).planePose(face.frame, face.axisA, face.axisB)?.let { plane ->
            val origin = frameWorld(s, parent)?.origin ?: plane.origin
            Frame(origin, plane.rotation)
        }
        else -> frameWorld(s, parent)
    }

    private fun applyPose(s: CastState, opId: String, handle: VisualHandle, base: Frame, ownFrame: String, baseScale: Double) {
        val track = s.tracks.values.filter { it.op.target == opId }
        var pose = base
        var scale = baseScale
        for (t in track) {
            val space = t.op.space?.let { frameWorld(s, it) } ?: s.ctx.tree.worldOf(FrameId(ownFrame)).getOrNull() ?: base
            val sample = sampleTrack(s, t, s.tick - t.startTick)
            pose = Frame(
                pose.origin + space.directionToWorld(sample.offset),
                (pose.rotation * sample.rotation).normalized(),
            )
            scale *= sample.scale
        }
        val visuals = s.ctx.ports.visuals
        visuals.updatePose(handle, pose, 1)
        if (track.isNotEmpty()) visuals.updateScale(handle, scale)
    }

    private class Sample(val offset: Vec3, val rotation: Quaternion, val scale: Double)

    private fun sampleTrack(s: CastState, t: TrackState, elapsed: Int): Sample {
        val keys = t.op.keys.sortedBy { it.tick }
        val offsets = keys.filter { it.offset != null }
        val rots = keys.filter { it.rotation != null }
        val scales = keys.filter { it.scale != null }
        val v = s.ctx.values
        val off = channel(offsets, elapsed, { v.vec3(it.offset!!) }, Vec3(0.0, 0.0, 0.0)) { a, b, f -> a + (b - a) * f }
        val rot = channel(rots, elapsed, { v.vec3(it.rotation!!) }, Vec3(0.0, 0.0, 0.0)) { a, b, f -> a + (b - a) * f }
        val sc = channel(scales, elapsed, { v.num(it.scale!!) }, 1.0) { a, b, f -> a + (b - a) * f }
        return Sample(off, Quaternion.fromEulerDegrees(rot.x, rot.y, rot.z), sc)
    }

    private fun <T> channel(
        keys: List<TrackKey>,
        elapsed: Int,
        value: (TrackKey) -> T,
        neutral: T,
        lerp: (T, T, Double) -> T,
    ): T {
        if (keys.isEmpty()) return neutral
        if (elapsed <= keys.first().tick) return value(keys.first())
        if (elapsed >= keys.last().tick) return value(keys.last())
        val hi = keys.indexOfFirst { it.tick >= elapsed }
        val a = keys[hi - 1]
        val b = keys[hi]
        val raw = (elapsed - a.tick).toDouble() / max(1, b.tick - a.tick)
        return lerp(value(a), value(b), ease(b.ease, raw))
    }

    private fun ease(name: String?, f: Double): Double = when (name) {
        "ease_in" -> f * f
        "ease_out" -> 1.0 - (1.0 - f) * (1.0 - f)
        "ease_in_out" -> if (f < 0.5) 2.0 * f * f else 1.0 - 2.0 * (1.0 - f) * (1.0 - f)
        else -> f
    }
}
