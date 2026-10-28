package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.ModelPointSource
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
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
import com.astarworks.astera.core.domain.skillengine.frame.relativeTo
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.op.MeshModelKey
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.core.domain.skillengine.op.Repeat
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

        /** v1 `VfxTimeline.ANIMATION_DELAY_TICKS`: a target weight is read one tick behind the element clock (VfxPlayer.kt:426). */
        const val PULL_DELAY: Int = 1

        /** v1 `WorldMechanics.MAX_DISPLAYS_PER_CAST` (:631): text displays one cast may spawn. */
        const val MAX_TEXT_PER_CAST: Int = 32
        const val MAX_FRAME_DEPTH: Int = 64

        /** v1 `VfxTimeline.MAX_STEP_ROTATION_DEG` (:93). */
        const val MAX_STEP_ROTATION_DEG: Double = 170.0
        private val UPRIGHT = 0.7071
        private val ONE = Vec3(1.0, 1.0, 1.0)
        private val IDENTITY = Frame(Vec3.ZERO)
    }

    internal class TrackState(val op: VisualOp.Track, val startTick: Int)

    /** One chunk of a track-driven display's clock (v1 `VfxTimeline.Step`): track ticks [start]..[end]. */
    internal class Step(val start: Int, val end: Int)

    internal class PullState(val op: VisualOp.Pull, val startTick: Int)

    /** A following [VisualOp.Anchor]: [rotation] is the spawn rotation the frame keeps while its origin tracks the point. */
    internal class AnchorFollow(val op: VisualOp.Anchor, val rotation: Quaternion)

    internal sealed class Live {
        abstract val opId: String
        var handle: VisualHandle = VisualHandle(-1)

        /** The tree node of a mesh / plate is named by its op id. */
        val node: FrameId get() = FrameId(opId)

        /** Cast tick at which a repeated copy is removed (Repeat.life); null = stays. */
        var dieAt: Int? = null

        /** True while a target pull moved this display on the last update: it is written with interpolation 0 (v1 teleports it). */
        var pulledNow: Boolean = false

        /** Pose last sent to the gateway; null until the spawn tick's first write. */
        var shown: VisualPose? = null

        /** Last look a track key pushed to the gateway (writes only on change). */
        var shownAlpha: Double? = null
        var shownColor: String? = null
        var shownBlock: String? = null
        var shownText: String? = null

        /** Id of the repeated op this is a copy of; a track on that id moves every copy in its own slot axes. */
        val group: String? get() = if (opId.contains('#')) opId.substringBeforeLast('#') else null

        /** A model on a node: base local origin [pivot], base turn [tilt], resting orientation [rest] inside the track turn. */
        class Mesh(
            override val opId: String,
            val parent: String,
            val scale: Double,
            val pivot: Vec3,
            val tilt: Quaternion,
            val rest: Quaternion,
            val models: List<MeshModelKey> = emptyList(),
        ) : Live() {
            /** Item model last pushed to the gateway (writes only on change). */
            var shownModel: String? = null
        }

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
            val at: Vec3 = Vec3.ZERO,
            val turn: Quaternion = Quaternion.IDENTITY,
        ) : Live() {
            var shownFrame: Int = 0
        }

        /** A block display on a node: per-axis [size] in blocks, base local origin [at] and base turn [turn]. */
        class Block(
            override val opId: String, val parent: String, val size: Vec3, val at: Vec3, val turn: Quaternion,
        ) : Live()

        /** A text-display quad on a node: [size] = (width, height) in blocks (a text glyph: blocks per text unit). */
        class Quad(
            override val opId: String, val parent: String, val face: PlateFace, val size: Vec3, val billboard: DisplayBillboard,
            val at: Vec3, val turn: Quaternion,
        ) : Live()

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
            var lastPushed: Pair<Vec3, Vec3>? = null
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

        /** Chunk steps per animated display id, with the tracks they were cut from (recut when a track appears). */
        val chunkCache = HashMap<String, Pair<List<TrackState>, List<Step>?>>()
        var textSpawned: Int = 0
        val live = LinkedHashMap<String, Live>()
        val sounds = LinkedHashMap<String, SoundTask>()
        val tracks = LinkedHashMap<String, TrackState>()
        val pulls = LinkedHashMap<String, PullState>()
        val anchors = LinkedHashMap<String, AnchorFollow>()

        /** Anchor frame -> `teleport_duration` of the displays under it (see [VisualOp.Anchor.teleportTicks]). */
        val teleports = HashMap<String, Int>()
        val lastOrigin = HashMap<String, Vec3>()
        val velocity = HashMap<String, Vec3>()
        val watched = LinkedHashSet<String>()

        /** Projectile bodies that ended (hit / block / expire): a trail `emit at:` them stops with the projectile. */
        val endedBodies = LinkedHashSet<String>()

        /** Later copies of repeated displays / emits that have not run yet. */
        val pending = ArrayList<Pending>()

        /** Ids of the group nodes made for repeated displays. */
        val groups = LinkedHashSet<String>()
        val firings = HashMap<String, Int>()
        val instanceGroups = LinkedHashSet<String>()
        val rigs = ArrayList<RigPerformer>()
    }

    /** One later copy of the repeated op [group], due at cast tick [due]. */
    internal class Pending(val group: String, val due: Int, val run: () -> Unit)

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

    /** Starts tracking [frame]'s origin velocity; the origin seen now is the baseline, so the very next tick already has a velocity. */
    private fun watch(s: CastState, frame: String) {
        if (!s.watched.add(frame)) return
        s.ctx.tree.worldOf(FrameId(frame)).getOrNull()?.let { s.lastOrigin.putIfAbsent(frame, it.origin) }
    }

    /**
     * Frame origin velocity in blocks per tick: how far the frame moved since the end of the previous tick (presentation
     * runs after the tick's motion, so this is this tick's step). Starts tracking the frame on first request.
     */
    internal fun velocityOf(s: CastState, frame: String): Vec3 {
        watch(s, frame)
        val now = s.ctx.tree.worldOf(FrameId(frame)).getOrNull()?.origin ?: return s.velocity[frame] ?: Vec3(0.0, 0.0, 0.0)
        return s.lastOrigin[frame]?.let { now - it } ?: Vec3(0.0, 0.0, 0.0)
    }

    /**
     * A track whose target is a plain frame node (not a mesh / plate / line) moves that node: its local pose becomes the
     * track's offset and turn at this tick. Every child (emit point, mesh, plate, line end) rides it through the tree.
     */
    internal fun settleFrame(s: CastState, frame: String) {
        for (t in s.tracks.values) {
            if (t.op.target != frame || s.live.containsKey(frame) || frame in s.groups) continue
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

    /** [spawnNode] for a block/quad part: under a rig it is posed like a v1 rig part (pivot + wrist rotation at rig tick 0, VfxRigPlanner.kt:276). */
    internal fun spawnPart(s: CastState, live: Live, parent: String): VisualPose? =
        driverOf(s, parent).let { d -> if (d == null) spawnNode(s, live, parent) else d.withPartFrame(s, 0) { spawnNode(s, live, parent) } }

    /** Applies the tracks of [live] to its node and returns the pose to show; null when the parent is gone. */
    internal fun update(s: CastState, live: Live): VisualPose? {
        val tree = s.ctx.tree
        when (live) {
            is Live.Mesh -> {
                val parentW = frameWorld(s, live.parent) ?: return null
                val t = tracked(s, live.opId, parentW, live.group)
                val pull = pullOf(s, live)
                tree.setLocal(live.node, Frame(live.pivot + t.offset + pullShift(pull, parentW), (live.tilt * t.turn * live.rest).normalized()))
                val world = tree.worldOf(live.node).getOrNull() ?: return null
                val k = live.scale * tree.scaleOf(live.node)
                return VisualPose(world, Vec3(k * t.scale.x, k * t.scale.y, k * t.scale.z), DisplayBillboard.FIXED, anchorFeet(s, live.parent))
            }
            is Live.Plate -> {
                val parentW = frameWorld(s, live.parent) ?: return null
                val t = tracked(s, live.opId, parentW, live.group)
                val base = (live.face as? PlateFace.Plane)?.let { planeRotation(s, it) }
                    ?.let { parentW.rotation.conjugate() * it } ?: Quaternion.IDENTITY
                val pull = pullOf(s, live)
                tree.setLocal(live.node, Frame(live.at + live.turn.rotate(t.offset) + pullShift(pull, parentW), (base * live.turn * t.turn).normalized()))
                val world = tree.worldOf(live.node).getOrNull() ?: return null
                val frame = billboardFrame(s, live.opId, parentW, world, live.billboard, live.turn)
                val fs = tree.scaleOf(live.node)
                return VisualPose(frame, Vec3(live.size * fs * t.scale.x, live.size * fs * t.scale.y, fs * t.scale.z), live.billboard, anchorFeet(s, live.parent))
            }
            is Live.Block -> {
                val parentW = frameWorld(s, live.parent) ?: return null
                val t = tracked(s, live.opId, parentW, live.group)
                val pull = pullOf(s, live)
                tree.setLocal(live.node, Frame(live.at + live.turn.rotate(t.offset) + pullShift(pull, parentW), (live.turn * t.turn).normalized()))
                val world = tree.worldOf(live.node).getOrNull() ?: return null
                val fs = tree.scaleOf(live.node)
                val size = Vec3(live.size.x * fs * t.scale.x, live.size.y * fs * t.scale.y, live.size.z * fs * t.scale.z)
                if (pull != null && pull.line) return pulledLine(world, size, pull.correction, anchorFeet(s, live.parent))
                return VisualPose(world, size, DisplayBillboard.FIXED, anchorFeet(s, live.parent))
            }
            is Live.Quad -> {
                val parentW = frameWorld(s, live.parent) ?: return null
                val t = tracked(s, live.opId, parentW, live.group)
                val base = (live.face as? PlateFace.Plane)?.let { planeRotation(s, it) }
                    ?.let { parentW.rotation.conjugate() * it } ?: Quaternion.IDENTITY
                val pull = pullOf(s, live)
                tree.setLocal(live.node, Frame(live.at + live.turn.rotate(t.offset) + pullShift(pull, parentW), (base * live.turn * t.turn).normalized()))
                val world = tree.worldOf(live.node).getOrNull() ?: return null
                val frame = billboardFrame(s, live.opId, parentW, world, live.billboard, live.turn)
                val fs = tree.scaleOf(live.node)
                return VisualPose(frame, Vec3(live.size.x * fs * t.scale.x, live.size.y * fs * t.scale.y, fs * t.scale.z), live.billboard, anchorFeet(s, live.parent))
            }
            else -> return null
        }
    }

    /**
     * Frame written for a plate / quad. A viewer face is the vanilla billboard (the client turns the display toward the camera
     * and then applies `left_rotation`), so the frame's rotation is what v1 wrote as `left_rotation` (v1 VfxTimeline.kt:362-366,
     * `transformAt`): CENTER keeps only the element's own roll (base turn x its own tracks, no caster / group rotation, which the
     * camera already supplies); VERTICAL keeps the full placed rotation. FIXED is the world frame.
     */
    private fun billboardFrame(
        s: CastState, id: String, parentW: Frame, world: Frame, billboard: DisplayBillboard, baseTurn: Quaternion,
    ): Frame = when (billboard) {
        DisplayBillboard.FIXED, DisplayBillboard.VERTICAL -> world
        else -> Frame(world.origin, (baseTurn * tracked(s, id, parentW).turn).normalized())
    }

    private class Pulled(val correction: Vec3, val weight: Double, val line: Boolean)

    /**
     * v1 target tracking of [live] (VfxPlayer.kt:405-429): the correction `to - ref` and this tick's weight (the key clock is
     * the pull's own, one tick behind). Null when no pull targets it or its points no longer resolve.
     */
    private fun pullOf(s: CastState, live: Live): Pulled? {
        live.pulledNow = false
        if (s.pulls.isEmpty()) return null
        val p = s.pulls.values.firstOrNull { it.op.target == live.opId || it.op.target == live.group } ?: return null
        val ref = pointWorld(s, p.op.ref) ?: return null
        val to = pointWorld(s, p.op.to) ?: return null
        live.pulledNow = true
        return Pulled(to - ref, pullWeight(p.op.keys, s.tick - p.startTick - PULL_DELAY), p.op.line)
    }

    /** World offset of a plain pull, in the parent's axes; zero for a line pull (it stretches instead) or no pull. */
    private fun pullShift(pull: Pulled?, parentW: Frame): Vec3 =
        if (pull == null || pull.line) Vec3.ZERO else parentW.rotation.conjugate().rotate(pull.correction * pull.weight)

    /** v1 `targetWeightAt` (VfxTargetKey.kt:12): linear, holding the first / last weight outside the keyed interval. */
    private fun pullWeight(keys: List<com.astarworks.astera.core.domain.skillengine.op.PullKey>, t: Int): Double {
        if (keys.isEmpty() || t <= keys.first().tick) return keys.firstOrNull()?.weight ?: 0.0
        val next = keys.indexOfFirst { it.tick > t }
        if (next < 0) return keys.last().weight
        val a = keys[next - 1]
        val b = keys[next]
        return a.weight + (b.weight - a.weight) * (t - a.tick).toDouble() / (b.tick - a.tick)
    }

    /**
     * v1 `lineTransform` (VfxPlayer.kt:612): the display's length axis (z, [size].z long, centred on [world]) keeps its start
     * and its far end moves by [correction]; the display is re-aimed and stretched to the new length, up hint kept.
     */
    private fun pulledLine(world: Frame, size: Vec3, correction: Vec3, anchorFeet: Vec3?): VisualPose {
        val half = world.rotation.rotate(Vec3(0.0, 0.0, size.z * 0.5))
        val start = world.origin - half
        val end = world.origin + half + correction
        val delta = end - start
        val length = delta.length()
        if (length < 1e-9) return VisualPose(world, size, DisplayBillboard.FIXED, anchorFeet)
        val rot = Basis.facing(delta, world.rotation.rotate(Vec3(0.0, 1.0, 0.0)))
        return VisualPose(Frame((start + end) * 0.5, rot), Vec3(size.x, size.y, length), DisplayBillboard.FIXED, anchorFeet)
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

    /**
     * Ticks since [t] started, on the cast tick being processed (`s.tick` was advanced at the top of [tickCast]): the key of tick N is
     * the value written on tick N, as v1's element clock does (VfxPlayer.kt:335-338 ticker, :365-375 `age++`, :429 `u.tick <= age`).
     */
    private fun castElapsed(s: CastState, t: TrackState): Int = s.tick - 1 - t.startTick

    /**
     * Sum of every track on [target] at this tick; offsets are expressed in the track's space and returned in the parent's axes.
     * A display whose tracks have `sample_ticks` > 1 shows what v1 shows: the client interpolates linearly between the chunk-start and
     * chunk-end updates (v1 `updatesFor`, VfxTimeline.kt:424-436), so the value at a tick inside a chunk is the lerp (slerp for the
     * turn) of the two chunk ends, not the eased track value.
     */
    private fun tracked(s: CastState, target: String, parentW: Frame, group: String? = null): Tracked {
        val tracks = s.tracks.values.filter { it.op.target == target || (group != null && it.op.target == group) }
        val steps = chunkSteps(s, target, tracks)
        if (steps == null) return trackedAt(s, tracks, parentW, 0)
        val e = castElapsed(s, tracks.first())
        val step = steps.firstOrNull { e > it.start && e <= it.end } ?: return trackedAt(s, tracks, parentW, 0)
        val a = trackedAt(s, tracks, parentW, step.start - e)
        val b = trackedAt(s, tracks, parentW, step.end - e)
        val f = (e - step.start).toDouble() / (step.end - step.start)
        return Tracked(
            a.offset + (b.offset - a.offset) * f,
            Quaternion.slerp(a.turn, b.turn, f),
            a.scale + (b.scale - a.scale) * f,
        )
    }

    /** The tracks at their own tick plus [shift] (the chunk ends are sampled with it). */
    private fun trackedAt(s: CastState, tracks: List<TrackState>, parentW: Frame, shift: Int): Tracked {
        var offset = Vec3.ZERO
        var turn = Quaternion.IDENTITY
        var scale = ONE
        for (t in tracks) {
            val sample = sampleTrack(s, t, castElapsed(s, t) + shift)
            val space = t.op.space?.let { frameWorld(s, it) } ?: parentW
            offset += parentW.rotation.conjugate().rotate(space.directionToWorld(sample.offset))
            turn = (turn * sample.turn).normalized()
            scale = Vec3(scale.x * sample.scale.x, scale.y * sample.scale.y, scale.z * sample.scale.z)
        }
        return Tracked(offset, turn, scale)
    }

    /**
     * v1 `segmentSteps` (VfxTimeline.kt:544-559): between consecutive keyframe ticks (the element's and its group's tracks) the clock
     * is cut into `ceil(d / sampleTicks)` near-equal chunks; a chunk that turns more than [MAX_STEP_ROTATION_DEG] is halved
     * (`splitByRotation`, :561-566). Null = every tick is its own update (sample_ticks 1, or nothing animates the transform).
     */
    private fun chunkSteps(s: CastState, target: String, tracks: List<TrackState>): List<Step>? {
        if (tracks.isEmpty()) return null
        s.chunkCache[target]?.let { (cached, steps) -> if (cached == tracks) return steps }
        val sample = tracks.minOf { it.op.sampleTicks }
        val animated = tracks.any { t -> t.op.keys.any { it.offset != null || it.turn != null || it.scale != null } }
        var out: ArrayList<Step>? = null
        if (sample > 1 && animated) {
            out = ArrayList()
            val points = tracks.flatMap { t -> t.op.keys.map { it.tick } }.distinct().sorted()
            for (k in 1 until points.size) {
                val a = points[k - 1]
                val d = points[k] - a
                val n = (d + sample - 1) / sample
                var prev = a
                for (j in 1..n) {
                    val cut = a + Math.round(j.toDouble() * d / n).toInt()
                    splitByRotation(s, tracks, prev, cut, out)
                    prev = cut
                }
            }
        }
        s.chunkCache[target] = tracks to out
        return out
    }

    private fun splitByRotation(s: CastState, tracks: List<TrackState>, start: Int, end: Int, out: MutableList<Step>) {
        if (end - start >= 2 && turnDegrees(s, tracks, start, end) > MAX_STEP_ROTATION_DEG) {
            val mid = (start + end) / 2
            splitByRotation(s, tracks, start, mid, out)
            splitByRotation(s, tracks, mid, end, out)
        } else {
            out += Step(start, end)
        }
    }

    /** v1 `turnDegrees` (VfxTimeline.kt:568): the larger of the keyed rotation-vector delta and the quaternion angle between two track ticks. */
    private fun turnDegrees(s: CastState, tracks: List<TrackState>, start: Int, end: Int): Double {
        val v = s.ctx.values
        var keyedDelta = 0.0
        var qa = Quaternion.IDENTITY
        var qb = Quaternion.IDENTITY
        for (t in tracks) {
            val keys = t.op.keys.sortedBy { it.tick }
            val lerpV = { x: Vec3, y: Vec3, f: Double -> x + (y - x) * f }
            val a = channel(keys, start, { it.turn?.let { r -> v.vec3(r) } }, Vec3.ZERO, lerpV)
            val b = channel(keys, end, { it.turn?.let { r -> v.vec3(r) } }, Vec3.ZERO, lerpV)
            keyedDelta += maxOf(abs(b.x - a.x), abs(b.y - a.y), abs(b.z - a.z))
            qa = (qa * Quaternion.fromRotationVector(a)).normalized()
            qb = (qb * Quaternion.fromRotationVector(b)).normalized()
        }
        return max(keyedDelta, Math.toDegrees(qa.angleTo(qb)))
    }

    // ------------------------------------------------------------------ repeated ops

    /**
     * Runs [body] once per copy of [repeat] with `i` / `n` bound; copy k runs `every * k` ticks later. A display gets a plain
     * group node named [group] under [parent] first (a track / remove on it moves / removes every copy); [body] gets the
     * copy's id and parent and returns the live it spawned so `life` can be stamped. A null [repeat] runs [body] once.
     */
    internal fun repeated(
        s: CastState, ctx: OpContext, group: String, parent: String?, repeat: Repeat?,
        body: (id: String, parent: String?) -> Live?,
    ) {
        if (repeat == null) { body(group, parent); return }
        val n = Math.round(ctx.values.num(repeat.times)).toInt().coerceAtLeast(0)
        var groupName = group
        if (repeat.instance && parent != null) {
            // one instance per firing: a world-parented node at the parent's origin now (v1 resolves a Location once, TargeterResolver.kt:120)
            val origin = s.ctx.tree.worldOf(FrameId(parent)).getOrNull()?.origin ?: return
            groupName = "$group@${s.firings.merge(group, 1, Int::plus)}"
            s.ctx.tree.add(FrameNode(FrameId(groupName), FrameKind.PART, FrameId("world"), Frame(origin, Quaternion.IDENTITY))).getOrNull() ?: return
            s.groups += groupName
            s.instanceGroups += groupName
        } else {
            removeIds(s, listOf(group))
            if (parent != null) {
                if (s.ctx.tree.node(FrameId(parent)) == null) return
                s.ctx.tree.add(FrameNode(FrameId(group), FrameKind.PART, FrameId(parent), IDENTITY)).getOrNull() ?: return
                s.groups += group
            }
        }
        for (k in 0 until n) {
            // a delayed copy runs inside tickCast, after `s.tick` advanced: its life counts from the tick it is due, not the tick it runs
            val spawnTick = s.tick + k * repeat.every
            val run = {
                ctx.host.withIndex(k, n) {
                    val made = body("$groupName#$k", if (parent != null) groupName else null)
                    val life = repeat.life
                    if (made != null && life != null) made.dieAt = spawnTick + life
                }
            }
            if (k * repeat.every <= 0) run() else s.pending += Pending(group, spawnTick, run)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    internal fun register(s: CastState, live: Live) {
        s.live[live.opId] = live
    }

    /** Remove everything created by the listed op ids (objects, their tree nodes, repeating sounds, tracks). */
    internal fun removeIds(s: CastState, ids: List<String>) {
        for (id in ids) {
            s.pending.removeAll { it.group == id }
            if (s.instanceGroups.isNotEmpty() && id !in s.groups) {
                for (g in s.instanceGroups.filter { it.startsWith("$id@") }) removeIds(s, listOf(g))
            }
            if (id in s.groups) {
                s.instanceGroups.remove(id)
                for (copy in s.live.keys.filter { it.startsWith("$id#") }) removeIds(s, listOf(copy))
                s.groups.remove(id)
                s.ctx.tree.remove(FrameId(id))
            }
            s.rigs.firstOrNull { it.op.id == id }?.let { it.end(s, s.ctx.ports.visuals); s.rigs.remove(it) }
            s.live.remove(id)?.let {
                s.ctx.ports.visuals.remove(it.handle)
                if (it !is Live.Line && it !is Live.Trail) s.ctx.tree.remove(it.node)
            }
            s.sounds.remove(id)
            s.tracks.remove(id)
            s.pulls.remove(id)
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
        s.endedBodies += body
        val gone = s.live.values.filter { (it is Live.Mesh && it.parent == body) || (it is Live.Plate && it.parent == body) || (it is Live.Block && it.parent == body) || (it is Live.Quad && it.parent == body) }
        removeIds(s, gone.map { it.opId })
    }

    /**
     * v1 sends a display only when it changes (VfxPlayer.kt:430-441, an element's updates) and a display spawns at its final
     * transform, so the first write of a display carries interpolation 0 and an unchanged pose is not sent again.
     */
    private fun put(visuals: ISkillVisualGateway, l: Live, pose: VisualPose, ticks: Int) {
        val last = l.shown
        if (last == pose) return
        l.shown = pose
        visuals.write(l.handle, pose, if (last == null) 0 else ticks)
    }

    public fun tickCast(cast: CastId) {
        val s = casts[cast] ?: return
        s.tick++
        for (f in s.watched) {
            val o = s.ctx.tree.worldOf(FrameId(f)).getOrNull()?.origin ?: continue
            s.lastOrigin[f]?.let { prev -> s.velocity[f] = o - prev }
            s.lastOrigin[f] = o
        }
        if (s.pending.isNotEmpty()) {
            val due = s.pending.filter { it.due <= s.tick - 1 }
            s.pending.removeAll(due.toSet())
            due.forEach { it.run() }
        }
        for (l in s.live.values.filter { val d = it.dieAt; d != null && s.tick - 1 >= d }) removeIds(s, listOf(l.opId))
        // an instance whose copies are all gone (and none is still due) takes its group node with it
        for (g in s.instanceGroups.toList()) {
            if (s.live.keys.none { it.startsWith("$g#") } && s.pending.none { it.group == g }) removeIds(s, listOf(g))
        }
        for (target in s.tracks.values.map { it.op.target }.distinct()) if (!s.live.containsKey(target) && target !in s.groups) settleFrame(s, target)
        // v1 moves a following instance to its anchor every tick, the yaw baked at spawn (VfxPlayer.kt:414-420).
        for (a in s.anchors.values) {
            val node = s.ctx.tree.node(FrameId(a.op.frame)) ?: continue
            val origin = pointWorld(s, a.op.at) ?: continue
            val parentWorld = node.parent?.let { frameWorld(s, it.value) } ?: Frame(Vec3.ZERO)
            s.ctx.tree.setLocal(FrameId(a.op.frame), relativeTo(parentWorld, Frame(origin, a.rotation)))
        }
        // v1 ends the whole instance when its tracked target is gone (VfxPlayer.kt:407).
        for (p in s.pulls.values.toList()) {
            if (pointWorld(s, p.op.to) == null || pointWorld(s, p.op.ref) == null) {
                s.pulls.remove(p.op.id)
                removeIds(s, p.op.ends + p.op.target)
            }
        }
        val visuals = s.ctx.ports.visuals
        for (r in s.rigs.toList()) {
            if (!r.tick(s, visuals, s.tick - 1 - r.startTick)) {
                r.end(s, visuals)
                s.rigs.remove(r)
            }
        }
        for (l in s.live.values.toList()) {
            when (l) {
                is Live.Mesh -> {
                    update(s, l)?.let { pose ->
                        val driver = driverOf(s, l.parent)
                        if (driver == null) put(visuals, l, followed(s, pose, l.parent, l), if (l.pulledNow) 0 else DISPLAY_TICKS)
                        else driver.driven?.let { interp -> if (driver.changed(l.opId, pose)) visuals.write(l.handle, pose, interp) }
                    }
                    tickModel(s, l)
                }
                is Live.Plate -> {
                    update(s, l)?.let { put(visuals, l, followed(s, it, l.parent, l), if (l.pulledNow) 0 else DISPLAY_TICKS) }
                    tickFlipbook(s, l)
                    tickLook(s, l)
                }
                is Live.Block, is Live.Quad -> {
                    // parts hanging from a rig are written per turning chunk (v1 VfxRigPlanner.partPlan), not per rig tick
                    val parent = if (l is Live.Block) l.parent else (l as Live.Quad).parent
                    val driver = driverOf(s, parent)
                    if (driver == null) update(s, l)?.let { put(visuals, l, followed(s, it, parent, l), if (l.pulledNow) 0 else DISPLAY_TICKS) }
                    else driver.atPartChunk(s) { interp ->
                        update(s, l)?.let { pose -> if (driver.changed(l.opId, pose)) visuals.write(l.handle, pose, interp) }
                    }
                    tickLook(s, l)
                }
                is Live.Line -> linePose(s, l)?.let { put(visuals, l, it, DISPLAY_TICKS) }
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
            if (s.tick - t.stoppedAt >= t.ticks + t.lagTicks) {
                s.live.remove(t.opId)
                s.ctx.ports.visuals.remove(t.handle)
                return
            }
            // lag_ticks: the cross-sections still queued are drawn at the same cadence after the point stopped.
            if (t.lagged.isNotEmpty()) {
                val (r, p) = t.lagged.removeFirst()
                s.ctx.ports.visuals.pushTrailPoint(t.handle, r, p)
                t.lastPushed = r to p
                return
            }
            // A stopped trail stops growing but its quads keep fading (v1 VfxRigPlanner.trailPlans: quads live life_ticks after the rig ends):
            // re-pushing the last cross-section adds no quad and ages the existing ones.
            t.lastPushed?.let { (r, p) -> s.ctx.ports.visuals.pushTrailPoint(t.handle, r, p) }
            return
        }
        val tip = pointWorld(s, t.of) ?: return
        val root = t.root?.let { pointWorld(s, it) } ?: tip
        if (t.lagTicks <= 0) {
            s.ctx.ports.visuals.pushTrailPoint(t.handle, root, tip)
            t.lastPushed = root to tip
            return
        }
        // lag_ticks: the ribbon is drawn that many ticks behind the point.
        t.lagged.addLast(root to tip)
        if (t.lagged.size > t.lagTicks) {
            val (r, p) = t.lagged.removeFirst()
            s.ctx.ports.visuals.pushTrailPoint(t.handle, r, p)
            t.lastPushed = r to p
        }
    }

    /** Flipbook frame of a plate at the current tick: `(age / frameTicks)`, wrapped when [Live.Plate.loop], else held on the last frame. */
    internal fun flipbookFrame(frames: Int, frameTicks: Int?, loop: Boolean, age: Int): Int {
        if (frameTicks == null || frames <= 1) return 0
        val raw = max(0, age) / frameTicks
        return if (loop) raw % frames else minOf(raw, frames - 1)
    }

    /** Pushes the alpha / colour / block a track key sets on [l] (the last track that keys a channel wins); only on change. */
    private fun tickLook(s: CastState, l: Live) {
        val group = l.group
        var alpha: Double? = null
        var color: String? = null
        var block: String? = null
        var text: String? = null
        for (t in s.tracks.values) {
            if (t.op.target != l.opId && (group == null || t.op.target != group)) continue
            val look = sampleLook(s, t, castElapsed(s, t))
            look.alpha?.let { alpha = it }
            look.color?.let { color = it }
            look.block?.let { block = it }
            look.text?.let { text = it }
        }
        val visuals = s.ctx.ports.visuals
        val a = alpha?.takeIf { it != l.shownAlpha }
        val c = color?.takeIf { it != l.shownColor }
        if (a != null || c != null) {
            visuals.setLook(l.handle, a, c)
            if (a != null) l.shownAlpha = a
            if (c != null) l.shownColor = c
        }
        block?.takeIf { it != l.shownBlock }?.let { visuals.setBlock(l.handle, it); l.shownBlock = it }
        text?.takeIf { it != l.shownText }?.let { visuals.setText(l.handle, it); l.shownText = it }
    }

    /** Pushes the `item_model` step key of a rig-borne mesh that has come due on the rig's clock (v1 VfxBodyPlanner.withModelSwaps). */
    private fun tickModel(s: CastState, l: Live.Mesh) {
        if (l.models.isEmpty()) return
        val driver = driverOf(s, l.parent) ?: return
        val e = s.tick - 1 - driver.startTick
        val model = l.models.lastOrNull { driver.modelTick(it.tick) <= e && driver.modelTick(it.tick) < driver.op.durationTicks }?.model ?: return
        if (model != l.shownModel) {
            s.ctx.ports.visuals.setItemModel(l.handle, model)
            l.shownModel = model
        }
    }

    private class Look(val alpha: Double?, val color: String?, val block: String?, val text: String?)

    private fun sampleLook(s: CastState, t: TrackState, elapsed: Int): Look {
        val keys = t.op.keys.sortedBy { it.tick }
        val v = s.ctx.values
        val alphaKeys = keys.filter { it.alpha != null }
        val alpha = if (alphaKeys.isEmpty()) null else channel(alphaKeys, elapsed, { v.num(it.alpha!!) }, 1.0) { a, b, f -> a + (b - a) * f }
        val colorKeys = keys.filter { it.color != null }
        val color = if (colorKeys.isEmpty()) null else channel(colorKeys, elapsed, { rgbOf(it.color!!) }, Vec3(1.0, 1.0, 1.0)) { a, b, f -> a + (b - a) * f }
            .let { c -> "#%02x%02x%02x".format(Math.round(c.x).toInt().coerceIn(0, 255), Math.round(c.y).toInt().coerceIn(0, 255), Math.round(c.z).toInt().coerceIn(0, 255)) }
        val block = keys.lastOrNull { it.block != null && it.tick <= elapsed }?.block
            ?: keys.firstOrNull { it.block != null }?.block
        val text = keys.lastOrNull { it.text != null && it.tick <= elapsed }?.text
        return Look(alpha, color, block, text)
    }

    private fun rgbOf(hex: String): Vec3 = Vec3(hex.substring(1, 3).toInt(16).toDouble(), hex.substring(3, 5).toInt(16).toDouble(), hex.substring(5, 7).toInt(16).toDouble())

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
    /**
     * The pose with the `teleport_duration` of the followed anchor above [frame]; native passengers (caster-anchored) keep theirs,
     * and so does a display a track moves (v1 `selfMoving`: an element with position updates is not follow-teleported,
     * VfxPlayer.kt:163, :419).
     */
    private fun followed(s: CastState, pose: VisualPose, frame: String, l: Live): VisualPose {
        if (s.teleports.isEmpty() || pose.anchorFeet != null || selfMoving(s, l)) return pose
        var cur: FrameId? = FrameId(frame)
        var hops = 0
        while (cur != null && hops++ < MAX_FRAME_DEPTH) {
            s.teleports[cur.value]?.let { return pose.copy(teleportTicks = it) }
            cur = s.ctx.tree.node(cur)?.parent
        }
        return pose
    }

    private fun selfMoving(s: CastState, l: Live): Boolean {
        val group = l.group
        return s.tracks.values.any { t -> (t.op.target == l.opId || (group != null && t.op.target == group)) && t.op.keys.any { it.offset != null } }
    }

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
        val off = channel(keys, elapsed, { it.offset?.let { o -> v.vec3(o) } }, Vec3.ZERO) { a, b, f -> a + (b - a) * f }
        val turn = channel(
            keys, elapsed, { it.turn?.let { r -> Quaternion.fromRotationVector(v.vec3(r)) } }, Quaternion.IDENTITY,
        ) { a, b, f -> Quaternion.slerp(a, b, f) }
        val sc = channel(keys, elapsed, { it.scale?.let { c -> scaleOf(v, c) } }, ONE) { a, b, f -> a + (b - a) * f }
        return Sample(off, turn, sc)
    }

    /** A key's scale: a vec3 value as is; a `${param}` / number expression is a uniform factor. */
    private fun scaleOf(v: ValueResolver, value: Value<Vec3>): Vec3 = when (value) {
        is Value.Literal -> value.value
        else -> runCatching { v.vec3(value) }.getOrElse { val n = v.num(value as Value<Nothing>); Vec3(n, n, n) }
    }

    /**
     * One channel of a track at [elapsed]. v1 `resolveKeyframes` (VfxTimeline.kt:270-274): an absent field carries over from
     * the previous key and the state before the first key starts from the neutral value ([neutral]), so a key that omits the
     * channel keeps the earlier value and a first key without it eases from the neutral value, never from a later key's value.
     */
    private fun <T> channel(
        keys: List<TrackKey>,
        elapsed: Int,
        value: (TrackKey) -> T?,
        neutral: T,
        lerp: (T, T, Double) -> T,
    ): T {
        if (keys.isEmpty()) return neutral
        var cur = neutral
        val vals = keys.map { k -> cur = value(k) ?: cur; cur }
        if (elapsed <= keys.first().tick) return vals.first()
        if (elapsed >= keys.last().tick) return vals.last()
        val hi = keys.indexOfFirst { it.tick >= elapsed }
        val a = keys[hi - 1]
        val b = keys[hi]
        val raw = (elapsed - a.tick).toDouble() / max(1, b.tick - a.tick)
        return lerp(vals[hi - 1], vals[hi], ease(b.ease, raw))
    }

    private fun ease(name: String?, f: Double): Double = name?.let { com.astarworks.astera.core.domain.animation.Easing.fromKey(it) }?.apply(f) ?: f
}
