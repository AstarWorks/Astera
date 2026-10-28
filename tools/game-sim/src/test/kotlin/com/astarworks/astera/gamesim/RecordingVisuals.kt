package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.RibbonMesh
import com.astarworks.astera.combat.adapter.paper.skillengine.SkillDisplaySpecs
import com.astarworks.astera.core.application.skillengine.load.MeshModels
import com.astarworks.astera.core.domain.sprite.SpriteGlyphs
import com.astarworks.astera.core.domain.sprite.SpriteId
import com.astarworks.astera.combat.adapter.paper.skillengine.Intrinsic
import com.astarworks.astera.combat.adapter.paper.skillengine.ribbonColumns
import com.astarworks.astera.combat.adapter.paper.skillengine.toDisplayWrite
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
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
    /** Plates only: the glyph's size in blocks as the client draws it (display scale x font height px x 0.025), else 0. */
    val renderedW: Double = 0.0,
    val renderedH: Double = 0.0,
)

/** One display's life as the client sees it: spawn tick, the tick it was removed (null = never within the run) and what it is. */
class DisplayLife(val handle: Long, val kind: String, val note: String, val spawnTick: Int, var removeTick: Int? = null)

/** One display write as the client receives it: [write] at [tick], with the caster feet then (anchored writes are relative to them). */
class DisplayCall(val tick: Int, val handle: Long, val write: DisplayWrite, val feet: Vec3, val spawn: Boolean = false)

/**
 * Records the display matrices the Paper visual gateway would build (the same [toDisplayWrite] the Paper gateway calls),
 * one [DisplayFrame] per spawn / update, so a scenario can check them for stretch (SKL-14).
 */
class RecordingVisuals(
    private val now: () -> Int,
    private val casterFeet: () -> Vec3 = { Vec3.ZERO },
    /** `content/frames` tables: resolves a mesh to its pack model like the Paper gateway does. */
    private val frameTables: Map<String, com.astarworks.astera.core.application.skillengine.load.ItemFrames> = emptyMap(),
) : ISkillVisualGateway {
    override fun setTrailLook(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, fadePower: Double, style: String) {}
    /** The vanilla display each spawn would create (kind, text, billboard, brightness), by handle: what the Paper gateway hands `IDisplayEntityGateway.spawn`. */
    val specs = HashMap<Long, com.astarworks.astera.core.domain.effect.DisplaySpec>()

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
    private class Mesh
    private class Plate(val intrinsic: Intrinsic)
    private class Line
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
        private val frameCache = HashMap<String, Int>()
        private val spriteDir: java.io.File? = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { it.resolve("content/sprites") }.firstOrNull { it.isDirectory }
    }

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose, brightness: Int?): VisualHandle {
        val h = next(Mesh(), "mesh", "$item/$part")
        val w = write(h.id, "mesh", Intrinsic.ITEM_FIXED, pose, 0, pose.size.x, "$item/$part", spawn = true)
        val kind = SkillDisplaySpecs.meshKind(MeshModels.resolve(item, part, frameTables))
            ?: com.astarworks.astera.core.domain.effect.DisplayKind.Item(com.astarworks.astera.core.domain.item.ItemId("main-hand"), com.astarworks.astera.core.domain.effect.DisplayItemMode.FIXED, null)
        specs[h.id] = SkillDisplaySpecs.mesh(kind, w.transform, brightness)
        return h
    }

    override fun spawnSkinBone(castOwner: UUID, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose): VisualHandle {
        val h = next(Mesh(), "skin_bone", bone.yamlName)
        val w = write(h.id, "skin_bone", Intrinsic.ITEM_FIXED, pose, 0, pose.size.x, bone.yamlName, spawn = true)
        specs[h.id] = SkillDisplaySpecs.skinBone(com.astarworks.astera.core.domain.effect.DisplayKind.SkinBone(bone, slim, ghost), w.transform)
        return h
    }

    override fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, pose: VisualPose, brightness: Int?): VisualHandle {
        val intrinsic = Intrinsic.textGlyph(spritePixels(sprite))
        val h = next(Plate(intrinsic), "plate", sprite)
        spriteOf[h.id] = sprite
        val w = write(h.id, "plate", intrinsic, pose, 0, pose.size.x, sprite, spawn = true)
        specs[h.id] = SkillDisplaySpecs.plate(SpriteGlyphs.text(SpriteId(sprite), 0), alpha, w.transform, pose.billboard, brightness ?: 15)
        return h
    }

    private class Block
    private class Quad(val intrinsic: Intrinsic)

    override fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?): VisualHandle {
        val h = next(Block(), "block", block)
        val w = write(h.id, "block", Intrinsic.BLOCK, pose, 0, pose.size.x, block, spawn = true)
        specs[h.id] = SkillDisplaySpecs.block(block, w.transform, brightness)
        return h
    }

    override fun spawnQuad(castOwner: UUID, colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?): VisualHandle {
        val intrinsic = if (text == null) Intrinsic.TEXT_SOLID else Intrinsic.QUAD_GLYPH
        val h = next(Quad(intrinsic), "quad", text ?: colorHex)
        val w = write(h.id, "quad", intrinsic, pose, 0, pose.size.x, "wh=${pose.size.x},${pose.size.y}", spawn = true)
        specs[h.id] = SkillDisplaySpecs.quad(colorHex, alpha, text, shadow, seeThrough, w.transform, pose.billboard, brightness)
        return h
    }

    override fun spawnLine(castOwner: UUID, colorHex: String, pose: VisualPose): VisualHandle {
        val h = next(Line(), "line")
        val w = write(h.id, "line", Intrinsic.TEXT_SOLID, pose, 0, pose.size.y, "len=${"%.2f".format(pose.size.x)}", spawn = true)
        specs[h.id] = SkillDisplaySpecs.line(colorHex, w.transform)
        return h
    }

    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle = next(Trail(widthFrac, colorHex, alpha), "trail")

    /** Every flipbook frame / tint change of a plate: (tick, handle, frame, tint). */
    val plateFrames = ArrayList<Triple<Int, Long, Pair<Int, String?>>>()

    override fun setPlateFrame(handle: VisualHandle, frame: Int, tintHex: String?) { plateFrames += Triple(now(), handle.id, frame to tintHex) }

    /** Every alpha / colour change a track key pushed: (tick, handle, alpha, colour). */
    val looks = ArrayList<Triple<Int, Long, Pair<Double?, String?>>>()

    /** Every block switch a track key pushed: (tick, handle, block). */
    val blockSwaps = ArrayList<Triple<Int, Long, String>>()

    override fun setLook(handle: VisualHandle, alpha: Double?, colorHex: String?) { looks += Triple(now(), handle.id, alpha to colorHex) }

    override fun setBlock(handle: VisualHandle, block: String) { blockSwaps += Triple(now(), handle.id, block) }

    override fun sprite(sprite: String): SpriteInfo = SpriteInfo(spriteFrames(sprite), spritePixels(sprite))

    override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) {
        when (val e = entries[handle.id]) {
            is Mesh -> write(handle.id, "mesh", Intrinsic.ITEM_FIXED, pose, interpolationTicks, pose.size.x)
            is Plate -> write(handle.id, "plate", e.intrinsic, pose, interpolationTicks, pose.size.x, "wh=${pose.size.x},${pose.size.y}")
            is Block -> write(handle.id, "block", Intrinsic.BLOCK, pose, interpolationTicks, pose.size.x)
            is Quad -> write(handle.id, "quad", e.intrinsic, pose, interpolationTicks, pose.size.x, "wh=${pose.size.x},${pose.size.y}")
            is Line -> write(handle.id, "line", Intrinsic.TEXT_SOLID, pose, interpolationTicks, pose.size.y, "len=${"%.2f".format(pose.size.x)}")
        }
    }

    /** The Paper gateway's write, recorded: [toDisplayWrite] of the pose, then the same fields as a [DisplayCall] and a [DisplayFrame]. */
    private fun write(id: Long, kind: String, intrinsic: Intrinsic, pose: VisualPose, ticks: Int, authored: Double, note: String = "", spawn: Boolean = false): DisplayWrite {
        val w = toDisplayWrite(pose.frame, pose.size, intrinsic, pose.billboard, ticks, if (kind == "line") null else anchorAt(pose.anchorFeet))
        calls += DisplayCall(now(), id, w, casterFeet(), spawn)
        record(kind, id, authored, w.transform, note)
        return w
    }

    /** Frames in a sprite's flipbook (`frames:` of `content/sprites/<id>.yaml`). */
    private fun spriteFrames(sprite: String): Int = frameCache.getOrPut(sprite) {
        val f = spriteDir?.resolve("$sprite.yaml")
        f?.takeIf { it.isFile }?.readLines()?.firstNotNullOfOrNull { l -> l.removePrefix("frames:").takeIf { l.startsWith("frames:") }?.trim()?.toIntOrNull() } ?: 1
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
            val m = ribbonColumns(s.u, s.v, s.normal)
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

    override fun remove(handle: VisualHandle) { entries.remove(handle.id); lives[handle.id]?.let { if (it.removeTick == null) it.removeTick = now() } }
}
