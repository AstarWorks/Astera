package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
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

    internal companion object {
        /** Client interpolation length of every per-tick display write; tracks are sampled this many ticks ahead. */
        const val DISPLAY_LEAD: Int = 1
        const val MAX_FRAME_DEPTH: Int = 64
    }

    internal class TrackState(val op: VisualOp.Track, val startTick: Int)

    internal sealed class Live {
        abstract val opId: String
        abstract val handle: VisualHandle

        class Mesh(
            override val opId: String,
            override val handle: VisualHandle,
            val frame: String,
            val baseScale: Double,
            val pivot: Vec3 = Vec3(0.0, 0.0, 0.0),
            val tilt: Quaternion = Quaternion.IDENTITY,
            val rest: Quaternion = Quaternion.IDENTITY,
        ) : Live()
        class Plate(
            override val opId: String,
            override val handle: VisualHandle,
            val parent: String,
            val face: PlateFace,
            val baseSize: Double,
            val sprite: String = "",
            val frames: Int = 1,
            val frameTicks: Int? = null,
            val loop: Boolean = false,
            val tint: String? = null,
            val spawnTick: Int = 0,
        ) : Live() {
            var shownFrame: Int = 0
        }

        class Line(override val opId: String, override val handle: VisualHandle, val from: PointRef, val to: PointRef) : Live()
        class Trail(
            override val opId: String,
            override val handle: VisualHandle,
            val of: PointRef,
            val root: PointRef?,
            val ticks: Int,
            val untilEvent: String?,
            val lagTicks: Int = 0,
        ) : Live() {
            var stoppedAt: Int = -1
            val lagged = ArrayDeque<Pair<Vec3, Vec3>>()
        }
    }

    internal class SoundTask(
        val opId: String, val every: Int, val until: String?, val startTick: Int, var next: Int, val play: (elapsed: Int) -> Unit,
        /** Last play tick, in cast ticks (a pitch glide ends there, including a final play exactly on it). */
        val endTick: Int? = null,
    )

    internal class AnimRun(val owner: java.util.UUID, val anim: String, val until: String?)

    internal class CastState(val ctx: OpContext) {
        val anims = ArrayList<AnimRun>()
        var tick: Int = 0
        val live = LinkedHashMap<String, Live>()
        val sounds = LinkedHashMap<String, SoundTask>()
        val tracks = LinkedHashMap<String, TrackState>()
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

    public fun endCast(cast: CastId, cancelled: Boolean = false) {
        val s = casts.remove(cast) ?: return
        // One failing gateway call must not leak the remaining handles.
        for (a in s.anims) runCatching { if (cancelled) casterAnim.stop(a.owner, a.anim) else casterAnim.release(a.owner, a.anim) }
        for (l in s.live.values) runCatching { s.ctx.ports.visuals.remove(l.handle) }
    }

    /** A projectile body ended (hit, block, expire): the meshes and plates riding that body go with it, on that tick. */
    public fun endBody(cast: CastId, body: String) {
        val s = casts[cast] ?: return
        val gone = s.live.values.filter { (it is Live.Mesh && it.frame == body) || (it is Live.Plate && it.parent == body) }
        removeIds(s, gone.map { it.opId })
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
                    applyPose(s, l.opId, l.handle, rigBase(base, l.pivot, l.tilt), l.frame, l.baseScale, l.rest)
                }
                is Live.Plate -> {
                    val base = platePose(s, l.parent, l.face) ?: continue
                    applyPose(s, l.opId, l.handle, base, l.parent, l.baseSize, Quaternion.IDENTITY)
                    tickFlipbook(s, l)
                }
                is Live.Line -> {
                    val a = pointWorld(s, l.from) ?: continue
                    val b = pointWorld(s, l.to) ?: continue
                    visuals.updateLine(l.handle, a, b)
                }
                is Live.Trail -> tickTrail(s, l)
            }
        }
        if (s.anims.isNotEmpty()) {
            val done = s.anims.filter { it.until != null && info.hasRaised(cast, it.until) }
            for (a in done) casterAnim.stop(a.owner, a.anim)
            s.anims.removeAll(done)
        }
        // `s.tick` was advanced at the top of this call, so the cast tick being processed is one less
        val castTick = s.tick - 1
        for (t in s.sounds.values.toList()) {
            if (t.until != null && info.hasRaised(cast, t.until)) {
                s.sounds.remove(t.opId)
            } else if (castTick >= t.next) {
                // exact multiples of `every` from the op's own tick (0, N, 2N ...), never drifting with a late tick
                t.next = t.next + t.every
                if (t.endTick != null) t.next = minOf(t.next, t.endTick)
                t.play(castTick - t.startTick)
                if (t.endTick != null && castTick >= t.endTick) s.sounds.remove(t.opId)
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
        if (t.lagTicks <= 0) {
            s.ctx.ports.visuals.pushTrailPoint(t.handle, root, tip)
            return
        }
        // lag_ticks: the ribbon is drawn that many ticks behind the point.
        t.lagged.addLast(root to tip)
        if (t.lagged.size > t.lagTicks) {
            val (r, p) = t.lagged.removeFirst()
            s.ctx.ports.visuals.pushTrailPoint(t.handle, r, p)
        }
    }

    /** World pose of a plate: parent pose for viewer faces (the adapter billboards), plane pose otherwise. */
    internal fun platePose(s: CastState, parent: String, face: PlateFace): Frame? = when (face) {
        is PlateFace.Plane -> axes(s).planePose(face.frame, face.axisA, face.axisB)?.let { plane ->
            val origin = frameWorld(s, parent)?.origin ?: plane.origin
            Frame(origin, plane.rotation)
        }
        else -> frameWorld(s, parent)
    }

    /** The frame a rig mesh hangs from: [pivot] (frame axes) away from [base], with the swing plane [tilt]ed. */
    internal fun rigBase(base: Frame, pivot: Vec3, tilt: Quaternion): Frame =
        Frame(base.origin + base.directionToWorld(pivot), (base.rotation * tilt).normalized())

    /** Flipbook frame of a plate at the current tick: `(age / frameTicks)`, wrapped when [Live.Plate.loop], else held on the last frame. */
    internal fun flipbookFrame(frames: Int, frameTicks: Int?, loop: Boolean, age: Int): Int {
        if (frameTicks == null || frames <= 1) return 0
        val raw = max(0, age) / frameTicks
        return if (loop) raw % frames else minOf(raw, frames - 1)
    }

    private fun tickFlipbook(s: CastState, l: Live.Plate) {
        val frame = flipbookFrame(l.frames, l.frameTicks, l.loop, s.tick - l.spawnTick)
        if (frame == l.shownFrame) return
        l.shownFrame = frame
        s.ctx.ports.visuals.setPlateFrame(l.handle, frame, l.tint)
    }

    private fun applyPose(s: CastState, opId: String, handle: VisualHandle, base: Frame, ownFrame: String, baseScale: Double, rest: Quaternion) {
        val track = s.tracks.values.filter { it.op.target == opId }
        var pose = base
        var scale = Vec3(baseScale, baseScale, baseScale)
        for (t in track) {
            val space = t.op.space?.let { frameWorld(s, it) } ?: s.ctx.tree.worldOf(FrameId(ownFrame)).getOrNull() ?: base
            // The write interpolates for DISPLAY_LEAD ticks, so the keyframe sampled here is on screen DISPLAY_LEAD ticks
            // from now: sample that tick so a keyframe at tick N is what the player sees at tick N (v1 did the same).
            val sample = sampleTrack(s, t, s.tick - t.startTick + DISPLAY_LEAD)
            pose = Frame(
                pose.origin + space.directionToWorld(sample.offset),
                (pose.rotation * sample.rotation).normalized(),
            )
            scale = Vec3(scale.x * sample.scale.x, scale.y * sample.scale.y, scale.z * sample.scale.z)
        }
        // ONE write: position, rotation and scale reach the client together and arrive together after DISPLAY_LEAD ticks.
        s.ctx.ports.visuals.updateDisplay(
            handle, Frame(pose.origin, (pose.rotation * rest).normalized()), scale, DISPLAY_LEAD, anchorFeet(s, ownFrame),
        )
    }

    /**
     * Whether [frame] hangs off the caster frame (hand, bone, item) and not off the world / an enemy / a contact: such a
     * display rides the caster natively (v1 ADR-0276) so a dash or knockback cannot leave it behind. A frame re-parented to
     * the world (a thrown piece) is not anchored from that tick on.
     */
    internal fun anchorFeet(s: CastState, frame: String): Vec3? =
        if (casterAnchored(s, frame)) s.ctx.tree.worldOf(FrameId("caster")).getOrNull()?.origin else null

    internal fun casterAnchored(s: CastState, frame: String): Boolean {
        var cur: FrameId? = FrameId(frame)
        var hops = 0
        while (cur != null && hops++ < MAX_FRAME_DEPTH) {
            val node = s.ctx.tree.node(cur) ?: return false
            when (node.kind) {
                FrameKind.CASTER -> return true
                FrameKind.WORLD, FrameKind.ENEMY, FrameKind.CONTACT -> return false
                else -> cur = node.parent
            }
        }
        return false
    }

    private class Sample(val offset: Vec3, val rotation: Quaternion, val scale: Vec3)

    private fun sampleTrack(s: CastState, t: TrackState, elapsed: Int): Sample {
        val keys = t.op.keys.sortedBy { it.tick }
        val offsets = keys.filter { it.offset != null }
        val rots = keys.filter { it.rotation != null }
        val scales = keys.filter { it.scale != null }
        val v = s.ctx.values
        val off = channel(offsets, elapsed, { v.vec3(it.offset!!) }, Vec3(0.0, 0.0, 0.0)) { a, b, f -> a + (b - a) * f }
        val rot = channel(rots, elapsed, { v.vec3(it.rotation!!) }, Vec3(0.0, 0.0, 0.0)) { a, b, f -> a + (b - a) * f }
        val sc = channel(scales, elapsed, { scaleOf(v, it.scale!!) }, Vec3(1.0, 1.0, 1.0)) { a, b, f -> a + (b - a) * f }
        return Sample(off, Quaternion.fromEulerDegrees(rot.x, rot.y, rot.z), sc)
    }

    /** A key's scale: a vec3 value as is; a `${param}` / number expression is a uniform factor. */
    private fun scaleOf(v: ValueResolver, value: Value<Vec3>): Vec3 = when (value) {
        is Value.Literal -> value.value
        else -> runCatching { v.vec3(value) }.getOrElse { val n = v.num(value as Value<Nothing>); Vec3(n, n, n) }
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
