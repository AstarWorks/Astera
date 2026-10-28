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
import java.util.UUID

/** One display transformation the Paper gateway would send: [kind] mesh / plate / line / ribbon, the authored size, the 3x3 columns. */
class DisplayFrame(
    val tick: Int, val kind: String, val handle: Long, val authored: Double,
    /** The three columns of the linear part (rotation * scale), already in world axes. */
    val cols: List<Vec3>,
    val rotationNormSq: Double,
    val note: String = "",
    /** Plates only: the glyph's size in blocks as the client draws it (display scale x font height px x 0.025), else 0. */
    val renderedW: Double = 0.0,
    val renderedH: Double = 0.0,
)

/** One display's life as the client sees it: spawn tick, the tick it was removed (null = never within the run) and what it is. */
class DisplayLife(val handle: Long, val kind: String, val note: String, val spawnTick: Int, var removeTick: Int? = null)

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
    private class Trail(val widthFrac: Double) { val mesh = RibbonMesh() }
    private val entries = HashMap<Long, Any>()

    private fun cols(rot: Quaternion, scale: Vec3) = listOf(
        rot.rotate(Vec3(1.0, 0.0, 0.0)) * scale.x, rot.rotate(Vec3(0.0, 1.0, 0.0)) * scale.y, rot.rotate(Vec3(0.0, 0.0, 1.0)) * scale.z,
    )

    val lives = HashMap<Long, DisplayLife>()
    private val spriteOf = HashMap<Long, String>()

    private fun record(kind: String, h: Long, authored: Double, t: DisplayTransform, note: String = "") {
        val px = if (kind == "plate") spritePixels(spriteOf[h] ?: "") else 0
        val w = if (kind == "plate") t.scale.x * px * GLYPH_BLOCKS_PER_PX else 0.0
        val hh = if (kind == "plate") t.scale.y * px * GLYPH_BLOCKS_PER_PX else 0.0
        frames += DisplayFrame(now(), kind, h, authored, cols(t.rotation, t.scale), t.rotation.lengthSquared(), note, w, hh)
    }

    private fun next(e: Any, kind: String, note: String = ""): VisualHandle {
        val id = ++ids; entries[id] = e; lives[id] = DisplayLife(id, kind, note, now()); return VisualHandle(id)
    }

    /** Font height of a sprite (`content/sprites/<id>.yaml` `size`), the figure the client scales a glyph by. */
    private fun spritePixels(sprite: String): Int = pixelCache.getOrPut(sprite) {
        val f = spriteDir?.resolve("$sprite.yaml")
        f?.takeIf { it.isFile }?.readLines()?.firstNotNullOfOrNull { l -> l.removePrefix("size:").takeIf { l.startsWith("size:") }?.trim()?.toIntOrNull() } ?: 32
    }

    companion object {
        /** TextDisplay: one font pixel is 0.025 blocks at scale 1 (vanilla TextDisplayRenderer, scale(-0.025)). */
        const val GLYPH_BLOCKS_PER_PX = 0.025
        private val pixelCache = HashMap<String, Int>()
        private val spriteDir: java.io.File? = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { it.resolve("content/sprites") }.firstOrNull { it.isDirectory }
    }

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, scale: Double, pose: Frame): VisualHandle {
        val h = next(Mesh(Vec3(scale, scale, scale), pose.rotation), "mesh", "$item/$part")
        record("mesh", h.id, scale, DisplayTransform(Vec3.ZERO, pose.rotation, Vec3(scale, scale, scale)), "$item/$part")
        return h
    }

    override fun spawnPlate(castOwner: UUID, sprite: String, face: PlateFace, size: Double, alpha: Double, pose: Frame): VisualHandle {
        val placed = PlateOrientation.forPlate(pose, face, null)
        val h = next(Plate(face, Vec3(size, size, size), placed.rotation), "plate", sprite)
        spriteOf[h.id] = sprite
        record("plate", h.id, size, SkillDisplayMath.plateTransform(placed.rotation, size, size), sprite)
        return h
    }

    override fun spawnLine(castOwner: UUID, from: Vec3, to: Vec3, thickness: Double, colorHex: String, face: PlateFace): VisualHandle {
        val h = next(Line(face, thickness), "line")
        record("line", h.id, thickness, SkillDisplayMath.lineTransform(from, to, thickness, face, null), "len=${"%.2f".format(from.distance(to))}")
        return h
    }

    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle = next(Trail(widthFrac), "trail")

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

    override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) {
        val t = entries[handle.id] as? Trail ?: return
        val span = root - tip
        val shaped = if (span.lengthSquared() < 1e-8) tip - Vec3(0.0, t.widthFrac, 0.0) else tip + span * t.widthFrac
        val s = t.mesh.push(shaped, tip) ?: return
        val m = SkillDisplayMath.ribbonColumns(s.u, s.v, s.normal)
        frames += DisplayFrame(now(), "ribbon", handle.id, t.widthFrac, listOf(m.col0, m.col1, m.col2), 1.0, "u=${"%.2f".format(s.u.length())} v=${"%.2f".format(s.v.length())}")
    }

    override fun remove(handle: VisualHandle) { entries.remove(handle.id); lives[handle.id]?.let { if (it.removeTick == null) it.removeTick = now() } }
}
