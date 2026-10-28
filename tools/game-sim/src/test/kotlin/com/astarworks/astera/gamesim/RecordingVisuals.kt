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
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import java.util.UUID

/** One display transformation the Paper gateway would send: [kind] mesh / plate / line / ribbon, the authored size, the 3x3 columns. */
class DisplayFrame(
    val tick: Int, val kind: String, val handle: Long, val authored: Double,
    /** The three columns of the linear part (rotation * scale), already in world axes. */
    val cols: List<Vec3>,
    val rotationNormSq: Double,
    val note: String = "",
)

/**
 * Records the display matrices the Paper visual gateway would build (same maths, [SkillDisplayMath] / [PlateOrientation]),
 * one [DisplayFrame] per spawn / update, so a scenario can check them for stretch (SKL-14).
 */
class RecordingVisuals(private val now: () -> Int) : ISkillVisualGateway {
    val frames = ArrayList<DisplayFrame>()
    private var ids = 0L
    private class Mesh(var scale: Vec3, var rotation: Quaternion)
    private class Plate(val face: PlateFace, var size: Vec3, var rotation: Quaternion)
    private class Line(val face: PlateFace, val thickness: Double)
    private class Trail(val widthFrac: Double, val colorHex: String, val alpha: Double) {
        val mesh = RibbonMesh()
        var bands: List<TrailBand> = emptyList()
        var edge: TrailEdge? = null
        var rim: TrailRim? = null
        var stepDeg = 30.0
        var doubleSided = false
    }

    /** One ribbon quad the Paper gateway would spawn: band index / kind and its colour + peak alpha. */
    class RibbonQuad(val handle: Long, val tick: Int, val kind: RibbonMesh.PieceKind, val index: Int, val colorHex: String, val alpha: Double, val colorToHex: String?, val backFace: Boolean)

    val ribbonQuads = ArrayList<RibbonQuad>()
    val trailShapes = HashMap<Long, Triple<List<TrailBand>, TrailEdge?, TrailRim?>>()
    private val entries = HashMap<Long, Any>()

    private fun cols(rot: Quaternion, scale: Vec3) = listOf(
        rot.rotate(Vec3(1.0, 0.0, 0.0)) * scale.x, rot.rotate(Vec3(0.0, 1.0, 0.0)) * scale.y, rot.rotate(Vec3(0.0, 0.0, 1.0)) * scale.z,
    )

    private fun record(kind: String, h: Long, authored: Double, t: DisplayTransform, note: String = "") {
        frames += DisplayFrame(now(), kind, h, authored, cols(t.rotation, t.scale), t.rotation.lengthSquared(), note)
    }

    private fun next(e: Any): VisualHandle { val id = ++ids; entries[id] = e; return VisualHandle(id) }

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, scale: Double, pose: Frame): VisualHandle {
        val h = next(Mesh(Vec3(scale, scale, scale), pose.rotation))
        record("mesh", h.id, scale, DisplayTransform(Vec3.ZERO, pose.rotation, Vec3(scale, scale, scale)), "$item/$part")
        return h
    }

    override fun spawnPlate(castOwner: UUID, sprite: String, face: PlateFace, size: Double, alpha: Double, pose: Frame): VisualHandle {
        val placed = PlateOrientation.forPlate(pose, face, null)
        val h = next(Plate(face, Vec3(size, size, size), placed.rotation))
        record("plate", h.id, size, SkillDisplayMath.plateTransform(placed.rotation, size, size), sprite)
        return h
    }

    override fun spawnLine(castOwner: UUID, from: Vec3, to: Vec3, thickness: Double, colorHex: String, face: PlateFace): VisualHandle {
        val h = next(Line(face, thickness))
        record("line", h.id, thickness, SkillDisplayMath.lineTransform(from, to, thickness, face, null), "len=${"%.2f".format(from.distance(to))}")
        return h
    }

    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle = next(Trail(widthFrac, colorHex, alpha))

    override fun updatePose(handle: VisualHandle, pose: Frame, interpolationTicks: Int) {
        when (val e = entries[handle.id]) {
            is Mesh -> { e.rotation = pose.rotation; record("mesh", handle.id, e.scale.x, DisplayTransform(Vec3.ZERO, pose.rotation, e.scale)) }
            is Plate -> {
                val placed = PlateOrientation.forPlate(pose, e.face, null)
                e.rotation = placed.rotation
                record("plate", handle.id, e.size.x, SkillDisplayMath.plateTransform(placed.rotation, e.size.x, e.size.y), "wh=${e.size.x},${e.size.y}")
            }
        }
    }

    override fun updateScale(handle: VisualHandle, scale: Vec3) {
        when (val e = entries[handle.id]) {
            is Mesh -> { e.scale = scale; record("mesh", handle.id, scale.x, DisplayTransform(Vec3.ZERO, e.rotation, scale)) }
            is Plate -> { e.size = scale; record("plate", handle.id, scale.x, SkillDisplayMath.plateTransform(e.rotation, scale.x, scale.y), "wh=${scale.x},${scale.y}") }
        }
    }

    override fun updateLine(handle: VisualHandle, from: Vec3, to: Vec3) {
        val e = entries[handle.id] as? Line ?: return
        record("line", handle.id, e.thickness, SkillDisplayMath.lineTransform(from, to, e.thickness, e.face, null), "len=${"%.2f".format(from.distance(to))}")
    }

    override fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) {
        val t = entries[handle.id] as? Trail ?: return
        t.bands = bands; t.edge = edge; t.rim = rim; t.stepDeg = stepDeg; t.doubleSided = doubleSided
        trailShapes[handle.id] = Triple(bands, edge, rim)
    }

    override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) {
        val t = entries[handle.id] as? Trail ?: return
        val span = root - tip
        val shaped = if (span.lengthSquared() < 1e-8) tip - Vec3(0.0, t.widthFrac, 0.0) else tip + span * t.widthFrac
        val bands = if (t.bands.isEmpty()) listOf(RibbonMesh.Band(0.0, 1.0)) else t.bands.map { RibbonMesh.Band(it.from, it.to) }
        for (piece in t.mesh.pushShaped(shaped, tip, bands, t.edge?.width, t.rim?.width, t.stepDeg)) {
            val s = piece.segment
            val m = SkillDisplayMath.ribbonColumns(s.u, s.v, s.normal)
            frames += DisplayFrame(now(), "ribbon", handle.id, t.widthFrac, listOf(m.col0, m.col1, m.col2), 1.0, "u=${"%.2f".format(s.u.length())} v=${"%.2f".format(s.v.length())}")
            val (hex, alpha, to) = when (piece.kind) {
                RibbonMesh.PieceKind.BAND ->
                    if (t.bands.isEmpty()) Triple(t.colorHex, t.alpha, null) else t.bands[piece.index].let { Triple(it.color, it.alpha, it.colorTo) }
                RibbonMesh.PieceKind.EDGE -> Triple(t.edge!!.color, 1.0, null)
                RibbonMesh.PieceKind.RIM -> Triple(t.rim!!.color, t.rim!!.alpha, null)
            }
            ribbonQuads += RibbonQuad(handle.id, now(), piece.kind, piece.index, hex, alpha, to, false)
            if (t.doubleSided && piece.kind != RibbonMesh.PieceKind.EDGE) ribbonQuads += RibbonQuad(handle.id, now(), piece.kind, piece.index, hex, alpha, to, true)
        }
    }

    override fun remove(handle: VisualHandle) { entries.remove(handle.id) }
}
