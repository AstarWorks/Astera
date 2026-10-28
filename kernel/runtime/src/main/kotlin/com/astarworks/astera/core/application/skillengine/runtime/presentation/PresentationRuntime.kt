package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.ModelPointSource
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.Axes
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import kotlin.math.abs
import kotlin.math.max

/**
 * Per-cast presentation table and per-tick updater (ADR-0303). A mesh or a plate is a NODE of the cast's frame tree under
 * its op id: parent frame plus a local pose, world = parent world composed with local. A track writes that node's local
 * pose (offset, turn quaternion, scale) and nothing else; each tick the node's WORLD frame goes to the gateway as one
 * [VisualPose] in blocks. Because the object is a tree node, any frame, emit, hit or child may use it as its parent.
 *
 * The cast runner calls [tickCast] once per tick after gameplay/handlers, and [endCast] when the cast ends or is cancelled.
 */
public class PresentationRuntime(
    internal val info: PresentationCastInfo,
    internal val casterAnim: ISkillCasterAnimGateway,
    internal val screen: ISkillScreenGateway,
    /** Point tables of item models: a mesh node carries the points of its (item, part). */
    internal val points: ModelPointSource = ModelPointSource { _, _ -> emptyList() },
) {
    private val casts = HashMap<CastId, CastState>()

    internal companion object {
        /** Client interpolation length of every per-tick display write. */
        const val DISPLAY_TICKS: Int = 1

        /** The client shows a write one tick later (interpolation), so a track is sampled one tick ahead: key N is on screen at tick N (v1 SKL-14). */
        const val DISPLAY_LEAD: Int = 1
        const val MAX_FRAME_DEPTH: Int = 64
        private val UPRIGHT = 0.7071
        private val ONE = Vec3(1.0, 1.0, 1.0)
        private val IDENTITY = Frame(Vec3.ZERO)
    }

    internal class TrackState(val op: VisualOp.Track, val startTick: Int)

    internal sealed class Live {
        abstract val opId: String
        var handle: VisualHandle = VisualHandle(-1)

        /** The tree node of a mesh / plate is named by its op id. */
        val node: FrameId get() = FrameId(opId)

        /** A model on a node: base local origin [pivot], base turn [tilt], resting orientation [rest] inside the track turn. */
        class Mesh(
            override val opId: String,
            val parent: String,
            val scale: Double,
            val pivot: Vec3,
            val tilt: Quaternion,
            val rest: Quaternion,
        ) : Live()

        class Plate(
            override val opId: String,
            val parent: String,
            val face: PlateFace,
            val size: Double,
            val billboard: DisplayBillboard,
            val sprite: String = "",
            val frames: Int = 1,
            val frameTicks: Int? = null,
            val loop: Boolean = false,
            val tint: String? = null,
            val spawnTick: Int = 0,
        ) : Live() {
            var shownFrame: Int = 0
        }

        class Line(override val opId: String, val from: PointRef, val to: PointRef, val thickness: Double, val face: PlateFace) : Live()

        class Trail(
            override val opId: String,
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
        val rigs = ArrayList<RigPerformer>()
    }

    internal fun state(ctx: OpContext): CastState = casts.getOrPut(ctx.cast) { CastState(ctx) }

    /** The running rig whose frame node [frame] is or hangs under (the displays riding it are written on its schedule); null if none. */
    internal fun driverOf(s: CastState, frame: String): RigPerformer? {
        if (s.rigs.isEmpty()) return null
        var cur: FrameId? = FrameId(frame)
        var hops = 0
        while (cur != null && hops++ < MAX_FRAME_DEPTH) {
            s.rigs.firstOrNull { it.op.frame == cur!!.value }?.let { return it }
            cur = s.ctx.tree.node(cur)?.parent
        }
        return null
    }

    /** Frame origin velocity in blocks per tick; starts tracking the frame on first request. */
    internal fun velocityOf(s: CastState, frame: String): Vec3 {
        s.watched += frame
        return s.velocity[frame] ?: Vec3(0.0, 0.0, 0.0)
    }

    /**
     * A track whose target is a plain frame node (not a mesh / plate / line) moves that node: its local pose becomes the
     * track's offset and turn at this tick. Every child (emit point, mesh, plate, line end) rides it through the tree.
     */
    internal fun settleFrame(s: CastState, frame: String) {
        for (t in s.tracks.values) {
            if (t.op.target != frame || s.live.containsKey(frame)) continue
            val node = s.ctx.tree.node(FrameId(frame)) ?: continue
            val parent = node.parent ?: continue
            val sample = sampleTrack(s, t, s.tick - t.startTick)
            val space = t.op.space?.let { frameWorld(s, it) }
            val parentW = s.ctx.tree.worldOf(parent).getOrNull() ?: continue
            val offset = if (space == null) sample.offset else parentW.rotation.conjugate().rotate(space.directionToWorld(sample.offset))
            s.ctx.tree.setLocal(FrameId(frame), Frame(offset, sample.turn))
        }
    }

    internal fun pointWorld(s: CastState, ref: PointRef): Vec3? {
        s.watched += ref.frame
        settleFrame(s, ref.frame)
        return s.ctx.tree.pointWorld(FrameId(ref.frame), ref.point).getOrNull()
    }

    internal fun frameWorld(s: CastState, frame: String): Frame? {
        s.watched += frame
        return s.ctx.tree.worldOf(FrameId(frame)).getOrNull()
    }

    /** World direction of [ref], one resolver for every consumer; a bare name is read relative to [self]. */
    internal fun axis(s: CastState, ref: AxisRef, self: String?): Vec3? {
        val name = ref.name
        val full = if ('.' in name || self == null) name else "$self.$name"
        val velocity = if (name == "velocity" || name == "velocity.side") self?.let { velocityOf(s, it) } else null
        return Axes.resolve(s.ctx.tree, full, velocity)
    }

    // ------------------------------------------------------------------ nodes

    /** Adds the tree node of a mesh / plate (replacing an earlier one of the same id) and returns its first pose. */
    internal fun spawnNode(s: CastState, live: Live, parent: String, itemPoints: List<FramePoint> = emptyList()): VisualPose? {
        val tree = s.ctx.tree
        removeIds(s, listOf(live.opId))
        if (tree.node(FrameId(parent)) == null) return null
        tree.add(FrameNode(live.node, FrameKind.PART, FrameId(parent), IDENTITY)).getOrNull() ?: return null
        itemPoints.forEach { tree.definePoint(it) }
        return update(s, live)
    }

    /** Applies the tracks of [live] to its node and returns the pose to show; null when the parent is gone. */
    internal fun update(s: CastState, live: Live): VisualPose? {
        val tree = s.ctx.tree
        when (live) {
            is Live.Mesh -> {
                val parentW = frameWorld(s, live.parent) ?: return null
                val t = tracked(s, live.opId, parentW)
                tree.setLocal(live.node, Frame(live.pivot + t.offset, (live.tilt * t.turn * live.rest).normalized()))
                val world = tree.worldOf(live.node).getOrNull() ?: return null
                val k = live.scale
                return VisualPose(world, Vec3(k * t.scale.x, k * t.scale.y, k * t.scale.z), DisplayBillboard.FIXED, anchorFeet(s, live.parent))
            }
            is Live.Plate -> {
                val parentW = frameWorld(s, live.parent) ?: return null
                val t = tracked(s, live.opId, parentW)
                val base = (live.face as? PlateFace.Plane)?.let { planeRotation(s, it) }
                    ?.let { parentW.rotation.conjugate() * it } ?: Quaternion.IDENTITY
                tree.setLocal(live.node, Frame(t.offset, (base * t.turn).normalized()))
                val world = tree.worldOf(live.node).getOrNull() ?: return null
                val fixed = live.billboard == DisplayBillboard.FIXED
                // A viewer face is the vanilla billboard: it owns the rotation, so the written frame carries only the origin.
                val frame = if (fixed) world else Frame(world.origin)
                return VisualPose(frame, Vec3(live.size * t.scale.x, live.size * t.scale.y, 1.0), live.billboard, anchorFeet(s, live.parent))
            }
            else -> return null
        }
    }

    /** World rotation of a plane face: local x = axis A, local y = axis B's part perpendicular to A. */
    internal fun planeRotation(s: CastState, face: PlateFace.Plane): Quaternion? {
        val a = axis(s, face.axisA, face.frame) ?: return null
        val b = axis(s, face.axisB, face.frame) ?: return null
        return Basis.basis(a, b)
    }

    /** Vanilla billboard of a face: a viewer face is the client billboard, a plane is fixed. */
    internal fun billboardOf(s: CastState, face: PlateFace, self: String): DisplayBillboard = when (face) {
        PlateFace.ViewerCenter -> DisplayBillboard.CENTER
        PlateFace.ViewerVertical -> DisplayBillboard.VERTICAL
        is PlateFace.ViewerAxis -> {
            val a = axis(s, face.axis, self)
            if (a != null && abs(a.y) >= UPRIGHT) DisplayBillboard.VERTICAL else DisplayBillboard.CENTER
        }
        is PlateFace.Plane -> DisplayBillboard.FIXED
    }

    /** Pose of a line between two points: centre, x along the line, size (length, thickness). No viewer is consulted. */
    internal fun linePose(s: CastState, l: Live.Line): VisualPose? {
        val a = pointWorld(s, l.from) ?: return null
        val b = pointWorld(s, l.to) ?: return null
        val along = b - a
        val length = along.length()
        if (length < 1e-9) return null
        val hint = when (val f = l.face) {
            is PlateFace.Plane -> {
                val n = Basis.axis(IDENTITY, f.axisA.name)?.cross(Basis.axis(IDENTITY, f.axisB.name) ?: Vec3.ZERO)
                n?.takeIf { it.lengthSquared() > 1e-12 }?.cross(along)
            }
            else -> null
        } ?: Vec3(0.0, 1.0, 0.0)
        val rot = Basis.basis(along, hint) ?: Basis.basis(along, Vec3(1.0, 0.0, 0.0)) ?: Quaternion.IDENTITY
        return VisualPose(Frame((a + b) * 0.5, rot), Vec3(length, l.thickness, 1.0), DisplayBillboard.FIXED, null)
    }

    private class Tracked(val offset: Vec3, val turn: Quaternion, val scale: Vec3)

    /** Sum of every track on [target] at this tick; offsets are expressed in the track's space and returned in the parent's axes. */
    private fun tracked(s: CastState, target: String, parentW: Frame): Tracked {
        var offset = Vec3.ZERO
        var turn = Quaternion.IDENTITY
        var scale = ONE
        for (t in s.tracks.values) {
            if (t.op.target != target) continue
            val sample = sampleTrack(s, t, s.tick - t.startTick + DISPLAY_LEAD)
            val space = t.op.space?.let { frameWorld(s, it) } ?: parentW
            offset += parentW.rotation.conjugate().rotate(space.directionToWorld(sample.offset))
            turn = (turn * sample.turn).normalized()
            scale = Vec3(scale.x * sample.scale.x, scale.y * sample.scale.y, scale.z * sample.scale.z)
        }
        return Tracked(offset, turn, scale)
    }

    // ------------------------------------------------------------------ lifecycle

    internal fun register(s: CastState, live: Live) {
        s.live[live.opId] = live
    }

    /** Remove everything created by the listed op ids (objects, their tree nodes, repeating sounds, tracks). */
    internal fun removeIds(s: CastState, ids: List<String>) {
        for (id in ids) {
            s.rigs.firstOrNull { it.op.id == id }?.let { it.end(s, s.ctx.ports.visuals); s.rigs.remove(it) }
            s.live.remove(id)?.let {
                s.ctx.ports.visuals.remove(it.handle)
                if (it is Live.Mesh || it is Live.Plate) s.ctx.tree.remove(it.node)
            }
            s.sounds.remove(id)
            s.tracks.remove(id)
        }
    }

    public fun endCast(cast: CastId, cancelled: Boolean = false) {
        val s = casts.remove(cast) ?: return
        for (r in s.rigs) runCatching { r.end(s, s.ctx.ports.visuals) }
        // One failing gateway call must not leak the remaining handles.
        for (a in s.anims) runCatching { if (cancelled) casterAnim.stop(a.owner, a.anim) else casterAnim.release(a.owner, a.anim) }
        for (l in s.live.values) runCatching { s.ctx.ports.visuals.remove(l.handle) }
    }

    /** A projectile body ended (hit, block, expire): the meshes and plates riding that body go with it, on that tick. */
    public fun endBody(cast: CastId, body: String) {
        val s = casts[cast] ?: return
        val gone = s.live.values.filter { (it is Live.Mesh && it.parent == body) || (it is Live.Plate && it.parent == body) }
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
        for (target in s.tracks.values.map { it.op.target }.distinct()) if (!s.live.containsKey(target)) settleFrame(s, target)
        val visuals = s.ctx.ports.visuals
        for (r in s.rigs.toList()) {
            if (!r.tick(s, visuals, s.tick - 1 - r.startTick)) {
                r.end(s, visuals)
                s.rigs.remove(r)
            }
        }
        for (l in s.live.values.toList()) {
            when (l) {
                is Live.Mesh -> update(s, l)?.let { pose ->
                    val driver = driverOf(s, l.parent)
                    if (driver == null) visuals.write(l.handle, pose, DISPLAY_TICKS)
                    else driver.driven?.let { interp -> if (driver.changed(l.opId, pose)) visuals.write(l.handle, pose, interp) }
                }
                is Live.Plate -> {
                    update(s, l)?.let { visuals.write(l.handle, it, DISPLAY_TICKS) }
                    tickFlipbook(s, l)
                }
                is Live.Line -> linePose(s, l)?.let { visuals.write(l.handle, it, DISPLAY_TICKS) }
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

    // ------------------------------------------------------------------ tracks

    private class Sample(val offset: Vec3, val turn: Quaternion, val scale: Vec3)

    /** A track at [elapsed]: offsets and scales lerp, turns slerp (shortest arc), each between its own keyed neighbours. */
    private fun sampleTrack(s: CastState, t: TrackState, elapsed: Int): Sample {
        val keys = t.op.keys.sortedBy { it.tick }
        val v = s.ctx.values
        val off = channel(keys.filter { it.offset != null }, elapsed, { v.vec3(it.offset!!) }, Vec3.ZERO) { a, b, f -> a + (b - a) * f }
        val turn = channel(
            keys.filter { it.turn != null }, elapsed, { Quaternion.fromRotationVector(v.vec3(it.turn!!)) }, Quaternion.IDENTITY,
        ) { a, b, f -> Quaternion.slerp(a, b, f) }
        val sc = channel(keys.filter { it.scale != null }, elapsed, { scaleOf(v, it.scale!!) }, ONE) { a, b, f -> a + (b - a) * f }
        return Sample(off, turn, sc)
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
