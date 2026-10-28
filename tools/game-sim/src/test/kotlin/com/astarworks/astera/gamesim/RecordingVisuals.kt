package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.RibbonMesh
import com.astarworks.astera.combat.adapter.paper.skillengine.PlateOrientation
import com.astarworks.astera.combat.adapter.paper.skillengine.SkillDisplayMath
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.combat.adapter.paper.skillengine.SkillDisplayWrites
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayWrite
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID

/** One display transformation the Paper gateway would send: [kind] mesh / plate / line / ribbon, the authored size, the 3x3 columns. */
class DisplayFrame(
    val tick: Int, val kind: String, val handle: Long, val authored: Double,
    /** The three columns of the linear part (rotation * scale), already in world axes. */
    val cols: List<Vec3>,
    val rotationNormSq: Double,
    val note: String = "",
)

/** One display write as the client receives it: [write] at [tick], with the caster feet then (anchored writes are relative to them). */
class DisplayCall(val tick: Int, val handle: Long, val write: DisplayWrite, val feet: Vec3, val spawn: Boolean = false)

/**
 * Records the display matrices the Paper visual gateway would build (same maths, [SkillDisplayMath] / [PlateOrientation]),
 * one [DisplayFrame] per spawn / update, so a scenario can check them for stretch (SKL-14).
 */
class RecordingVisuals(private val now: () -> Int, private val casterFeet: () -> Vec3 = { Vec3.ZERO }) : ISkillVisualGateway {
    val frames = ArrayList<DisplayFrame>()

    /** Every spawn / update write, in order: what the Paper gateway hands [com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.place]. */
    val calls = ArrayList<DisplayCall>()
    private val player = PlayerId(UUID(0, 1))
    private fun anchorAt(feet: Vec3?) = feet?.let { DisplayWrite.Anchor(player, it) }

    /**
     * Where the client draws display [handle] during tick [t] (>= its first write): the vanilla interpolation from the previous
     * target to the latest write's, over its duration; an anchored display is the caster's feet at [t] plus its offset
     * (a passenger moves with the caster instantly). Null before the first write.
     */
    fun shownAt(handle: Long, t: Int, feetAt: Vec3): Vec3? {
        val mine = calls.filter { it.handle == handle && it.tick <= t }
        val last = mine.lastOrNull() ?: return null
        val prev = mine.getOrNull(mine.size - 2)
        fun target(c: DisplayCall) = c.write.anchor?.let { c.write.at - it.feet } ?: c.write.at
        fun place(rel: Vec3, anchored: Boolean) = if (anchored) feetAt + rel else rel
        val to = place(target(last), last.write.anchor != null)
        val from = prev?.let { place(target(it), it.write.anchor != null) } ?: return to
        val d = last.write.teleportDuration
        val k = if (d <= 0) 1.0 else minOf(1.0, (t - last.tick + 1).toDouble() / d)
        return from + (to - from) * k
    }
    private var ids = 0L
    private class Mesh(var scale: Vec3, var rotation: Quaternion)
    private class Plate(val face: PlateFace, var size: Vec3, var rotation: Quaternion)
    private class Line(val face: PlateFace, val thickness: Double)
    private class Trail(val widthFrac: Double) { val mesh = RibbonMesh() }
    private val entries = HashMap<Long, Any>()

    private fun cols(rot: Quaternion, scale: Vec3) = listOf(
        rot.rotate(Vec3(1.0, 0.0, 0.0)) * scale.x, rot.rotate(Vec3(0.0, 1.0, 0.0)) * scale.y, rot.rotate(Vec3(0.0, 0.0, 1.0)) * scale.z,
    )

    private fun record(kind: String, h: Long, authored: Double, t: DisplayTransform, note: String = "") {
        frames += DisplayFrame(now(), kind, h, authored, cols(t.rotation, t.scale), t.rotation.lengthSquared(), note)
    }

    private fun next(e: Any): VisualHandle { val id = ++ids; entries[id] = e; return VisualHandle(id) }

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, scale: Double, pose: Frame, anchored: Boolean): VisualHandle {
        val h = next(Mesh(Vec3(scale, scale, scale), pose.rotation))
        calls += DisplayCall(now(), h.id, SkillDisplayWrites.mesh(pose, Vec3(scale, scale, scale), 0, anchorAt(casterFeet().takeIf { anchored })), casterFeet(), spawn = true)
        record("mesh", h.id, scale, DisplayTransform(Vec3.ZERO, pose.rotation, Vec3(scale, scale, scale)), "$item/$part")
        return h
    }

    override fun spawnPlate(castOwner: UUID, sprite: String, face: PlateFace, size: Double, alpha: Double, pose: Frame, anchored: Boolean): VisualHandle {
        val placed = PlateOrientation.forPlate(pose, face)
        val h = next(Plate(face, Vec3(size, size, size), placed.rotation))
        calls += DisplayCall(now(), h.id, SkillDisplayWrites.plate(pose, face, size, size, 0, anchorAt(casterFeet().takeIf { anchored })), casterFeet(), spawn = true)
        record("plate", h.id, size, SkillDisplayMath.plateTransform(placed.rotation, size, size), sprite)
        return h
    }

    override fun spawnLine(castOwner: UUID, from: Vec3, to: Vec3, thickness: Double, colorHex: String, face: PlateFace): VisualHandle {
        val h = next(Line(face, thickness))
        record("line", h.id, thickness, SkillDisplayMath.lineTransform(from, to, thickness, face, null), "len=${"%.2f".format(from.distance(to))}")
        return h
    }

    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle = next(Trail(widthFrac))

    override fun updateDisplay(handle: VisualHandle, pose: Frame, scale: Vec3, interpolationTicks: Int, anchorFeet: Vec3?) {
        val a = anchorAt(anchorFeet)
        when (val e = entries[handle.id]) {
            is Mesh -> {
                e.scale = scale; e.rotation = pose.rotation
                val w = SkillDisplayWrites.mesh(pose, scale, interpolationTicks, a)
                calls += DisplayCall(now(), handle.id, w, casterFeet())
                record("mesh", handle.id, scale.x, w.transform)
            }
            is Plate -> {
                e.size = scale
                val w = SkillDisplayWrites.plate(pose, e.face, scale.x, scale.y, interpolationTicks, a)
                e.rotation = w.transform.rotation
                calls += DisplayCall(now(), handle.id, w, casterFeet())
                record("plate", handle.id, scale.x, w.transform, "wh=${scale.x},${scale.y}")
            }
        }
    }

    override fun updateLine(handle: VisualHandle, from: Vec3, to: Vec3) {
        val e = entries[handle.id] as? Line ?: return
        record("line", handle.id, e.thickness, SkillDisplayMath.lineTransform(from, to, e.thickness, e.face, null), "len=${"%.2f".format(from.distance(to))}")
    }

    override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) {
        val t = entries[handle.id] as? Trail ?: return
        val span = root - tip
        val shaped = if (span.lengthSquared() < 1e-8) tip - Vec3(0.0, t.widthFrac, 0.0) else tip + span * t.widthFrac
        val s = t.mesh.push(shaped, tip) ?: return
        val m = SkillDisplayMath.ribbonColumns(s.u, s.v, s.normal)
        frames += DisplayFrame(now(), "ribbon", handle.id, t.widthFrac, listOf(m.col0, m.col1, m.col2), 1.0, "u=${"%.2f".format(s.u.length())} v=${"%.2f".format(s.v.length())}")
    }

    override fun remove(handle: VisualHandle) { entries.remove(handle.id) }
}
