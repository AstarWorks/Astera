package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle
import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.skillengine.load.ItemFrames
import com.astarworks.astera.core.application.skillengine.load.MeshModel
import com.astarworks.astera.core.application.skillengine.load.MeshModels
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.sprite.SpriteGlyphs
import com.astarworks.astera.core.domain.sprite.SpriteId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import com.astarworks.astera.core.domain.world.WorldId
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.joml.Matrix4f
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Paper [ISkillVisualGateway] (ADR-0301). Meshes, plates and lines are Display entities from the shared
 * [IDisplayEntityGateway], teleported with interpolation every tick; no passengers. Trails are one fixed text-quad
 * Display per ribbon segment (a sheared parallelogram needs a transformation matrix, which the shared gateway does not
 * carry). Main thread only.
 *
 * Orientation: the two full-viewer plate faces use the client billboard; axis-constrained and plane faces compute a
 * fixed rotation server-side ([PlateOrientation]) towards the nearest player.
 */
public class PaperSkillVisualGateway(
    private val server: Server,
    private val displays: IDisplayEntityGateway,
    /** Item point tables: a mesh part names its pack model there ([MeshModels]). */
    private val frameTables: Map<String, ItemFrames> = emptyMap(),
    /** Frames in a sprite's flipbook (`content/sprites/<id>.yaml`), 1 when unknown. */
    private val spriteFrameCount: (String) -> Int = { 1 },
) : ISkillVisualGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val ids = AtomicLong(1)
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val owners = ConcurrentHashMap<Long, UUID>()

    @Volatile
    private var lastWorld: WorldId? = null

    /** World of the most recent cast owner; the particle and sound ports carry no world (see report). */
    public fun activeWorld(): WorldId? = lastWorld

    private sealed interface Entry
    private class MeshEntry(val handle: DisplayHandle, var scale: Vec3, var rotation: Quaternion) : Entry
    private class PlateEntry(val handle: DisplayHandle, val face: PlateFace, var size: Vec3, val world: World, var rotation: Quaternion, val sprite: String) : Entry
    private class LineEntry(val handle: DisplayHandle, val face: PlateFace, val thickness: Double, val world: World) : Entry
    /** Colour of one ribbon quad: peak [alpha], the [rgbTo] it has faded to at the end of its life, and whether it keeps its alpha until then. */
    private class PieceStyle(val rgb: Int, val alpha: Double, val rgbTo: Int?, val crisp: Boolean)
    private class Seg(val display: UUID, var age: Int, val style: PieceStyle)
    private class TrailEntry(
        val world: World, val ticks: Int, val widthFrac: Double, val rgb: Int, val alpha: Double,
    ) : Entry {
        var fadePower: Double = 1.0
        var crisp: Boolean = false
        var bands: List<TrailBand> = emptyList()
        var edge: TrailEdge? = null
        var rim: TrailRim? = null
        var stepDeg: Double = 30.0
        var doubleSided: Boolean = false
        val mesh = RibbonMesh()
        val segments = ArrayDeque<Seg>()
    }

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, scale: Double, pose: Frame): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("mesh $item")
        val kind = when (val model = MeshModels.resolve(item, part, frameTables)) {
            is MeshModel.Pack -> DisplayKind.Item(ItemId("paper"), DisplayItemMode.FIXED, model.key)
            is MeshModel.Vanilla -> DisplayKind.Item(ItemId(model.material.replace('_', '-')), DisplayItemMode.FIXED, null)
            MeshModel.HeldItem -> {
                val held = server.getPlayer(castOwner)?.inventory?.itemInMainHand?.takeUnless { it.type.isAir }
                    ?: return dead("mesh $item (empty main hand)")
                DisplayKind.Item(ItemId(held.type.key.key.replace('_', '-')), DisplayItemMode.FIXED, held.itemMeta?.takeIf { it.hasItemModel() }?.itemModel?.toString())
            }
        }
        val spec = DisplaySpec(
            kind = kind,
            transform = DisplayTransform(Vec3.ZERO, pose.rotation, uniform(scale)),
            lifetimeTicks = LIFETIME,
            billboard = DisplayBillboard.FIXED,
            weaponRigShading = true,
        )
        val handle = displays.spawn(WorldId(world.name), pose.origin, spec, emptyList()) ?: return dead("mesh $item")
        return register(castOwner, MeshEntry(handle, uniform(scale), pose.rotation))
    }

    override fun spawnPlate(castOwner: UUID, sprite: String, face: PlateFace, size: Double, alpha: Double, pose: Frame): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("plate $sprite")
        val glyph = SpriteGlyphs.text(SpriteId(sprite), 0)
        val placed = PlateOrientation.forPlate(pose, face, viewerEye(world, pose.origin))
        val spec = DisplaySpec(
            kind = DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to glyph, LocaleCode.JA to glyph))),
                backgroundArgb = 0,
                shadow = false,
                seeThrough = false,
                textOpacity = opacity(alpha),
            ),
            transform = SkillDisplayMath.plateTransform(placed.rotation, size, size),
            lifetimeTicks = LIFETIME,
            billboard = placed.billboard,
            brightness = 15,
        )
        val handle = displays.spawn(WorldId(world.name), pose.origin, spec, listOf(glyph)) ?: return dead("plate $sprite")
        return register(castOwner, PlateEntry(handle, face, uniform(size), world, placed.rotation, sprite))
    }

    override fun spawnLine(castOwner: UUID, from: Vec3, to: Vec3, thickness: Double, colorHex: String, face: PlateFace): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("line")
        val argb = argb(colorHex)
        val mid = (from + to) * 0.5
        val spec = DisplaySpec(
            kind = DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to " ", LocaleCode.JA to " "))),
                backgroundArgb = argb,
                shadow = false,
                seeThrough = false,
                textOpacity = 255,
            ),
            transform = lineTransform(from, to, thickness, face, world),
            lifetimeTicks = LIFETIME,
            billboard = DisplayBillboard.FIXED,
            brightness = 15,
        )
        val handle = displays.spawn(WorldId(world.name), mid, spec, listOf(" ")) ?: return dead("line")
        return register(castOwner, LineEntry(handle, face, thickness, world))
    }

    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("trail")
        val rgb = argb(colorHex) and 0xFFFFFF
        return register(castOwner, TrailEntry(world, ticks.coerceAtLeast(1), widthFrac, rgb, alpha.coerceIn(0.0, 1.0)))
    }

    override fun spriteFrames(sprite: String): Int = spriteFrameCount(sprite).coerceAtLeast(1)

    override fun setPlateFrame(handle: VisualHandle, frame: Int, tintHex: String?) {
        val e = entries[handle.id] as? PlateEntry ?: return
        val rgb = tintHex?.let { argb(it) and 0xFFFFFF }
        displays.setText(e.handle, listOf(SpriteGlyphs.text(SpriteId(e.sprite), frame, rgb)))
    }

    override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) {
        val t = entries[handle.id] as? TrailEntry ?: return
        t.fadePower = fadePower.coerceAtLeast(0.0)
        t.crisp = style != "soft"
    }

    override fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) {
        val t = entries[handle.id] as? TrailEntry ?: return
        t.bands = bands
        t.edge = edge
        t.rim = rim
        t.stepDeg = stepDeg
        t.doubleSided = doubleSided
    }

    override fun updatePose(handle: VisualHandle, pose: Frame, interpolationTicks: Int) {
        when (val e = entries[handle.id]) {
            is MeshEntry -> {
                e.rotation = pose.rotation
                displays.moveTo(e.handle, pose.origin, 0f, 0f, interpolationTicks)
                displays.update(e.handle, DisplayTransform(Vec3.ZERO, pose.rotation, e.scale), interpolationTicks)
            }
            is PlateEntry -> {
                val placed = PlateOrientation.forPlate(pose, e.face, viewerEye(e.world, pose.origin))
                e.rotation = placed.rotation
                displays.moveTo(e.handle, pose.origin, 0f, 0f, interpolationTicks)
                displays.update(e.handle, SkillDisplayMath.plateTransform(placed.rotation, e.size.x, e.size.y), interpolationTicks)
            }
            else -> Unit
        }
    }

    override fun updateScale(handle: VisualHandle, scale: Vec3) {
        when (val e = entries[handle.id]) {
            is MeshEntry -> {
                e.scale = scale
                displays.update(e.handle, DisplayTransform(Vec3.ZERO, e.rotation, scale), SCALE_INTERPOLATION_TICKS)
            }
            is PlateEntry -> {
                e.size = scale
                displays.update(e.handle, SkillDisplayMath.plateTransform(e.rotation, scale.x, scale.y), SCALE_INTERPOLATION_TICKS)
            }
            else -> Unit
        }
    }

    override fun updateLine(handle: VisualHandle, from: Vec3, to: Vec3) {
        val e = entries[handle.id] as? LineEntry ?: return
        displays.moveTo(e.handle, (from + to) * 0.5, 0f, 0f, 1)
        displays.update(e.handle, lineTransform(from, to, e.thickness, e.face, e.world), 1)
    }

    override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) {
        val t = entries[handle.id] as? TrailEntry ?: return
        // Width: the root->tip span scaled by widthFrac around the tip; a point-only trail (root == tip) is a vertical ribbon.
        val span = root - tip
        val shaped = if (span.lengthSquared() < 1e-8) tip - Vec3(0.0, t.widthFrac, 0.0) else tip + span * t.widthFrac
        val ageStep = t.segments.iterator()
        while (ageStep.hasNext()) {
            val seg = ageStep.next()
            seg.age++
            if (seg.age >= t.ticks) {
                server.getEntity(seg.display)?.remove()
                ageStep.remove()
            } else {
                (server.getEntity(seg.display) as? TextDisplay)?.backgroundColor = colour(t, seg.style, seg.age)
            }
        }
        val bands = if (t.bands.isEmpty()) listOf(RibbonMesh.Band(0.0, 1.0)) else t.bands.map { RibbonMesh.Band(it.from, it.to) }
        val pieces = t.mesh.pushShaped(shaped, tip, bands, t.edge?.width, t.rim?.width, t.stepDeg)
        for (piece in pieces) {
            val style = styleOf(t, piece)
            val seg = piece.segment
            spawnSegment(t, seg, style)?.let { t.segments.addLast(Seg(it, 0, style)) }
            if (t.doubleSided && piece.kind != RibbonMesh.PieceKind.EDGE) {
                val back = RibbonMesh.Segment(seg.centre, seg.u * -1.0, seg.v, seg.normal * -1.0)
                spawnSegment(t, back, style)?.let { t.segments.addLast(Seg(it, 0, style)) }
            }
        }
    }

    private fun styleOf(t: TrailEntry, piece: RibbonMesh.Piece): PieceStyle = when (piece.kind) {
        RibbonMesh.PieceKind.BAND ->
            if (t.bands.isEmpty()) PieceStyle(t.rgb, t.alpha, null, t.crisp)
            else t.bands[piece.index].let { PieceStyle(argb(it.color) and 0xFFFFFF, it.alpha, it.colorTo?.let { c -> argb(c) and 0xFFFFFF }, t.crisp) }
        RibbonMesh.PieceKind.EDGE -> PieceStyle(argb(t.edge!!.color) and 0xFFFFFF, 1.0, null, true)
        RibbonMesh.PieceKind.RIM -> PieceStyle(argb(t.rim!!.color) and 0xFFFFFF, t.rim!!.alpha, null, false)
    }

    /** Removes every display [owner] still has (quit, death, world change): the safety net behind the cast-end removal. */
    public fun removeAllOf(owner: UUID): Int {
        val mine = owners.entries.filter { it.value == owner }.map { it.key }
        mine.forEach { remove(VisualHandle(it)) }
        return mine.size
    }

    /** Removes every display this gateway owns (plugin disable). */
    public fun removeAll(): Int {
        val all = entries.keys.toList()
        all.forEach { remove(VisualHandle(it)) }
        return all.size
    }

    /** Ribbon segments left in the world by an earlier run of the plugin (reload, crash): none belongs to a live trail at enable. */
    public fun sweepOrphans(): Int {
        val tag = com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry.OWNER_TAG_PREFIX + "skill-ribbon"
        var removed = 0
        for (world in server.worlds) for (e in world.entities) {
            if (tag in e.scoreboardTags) { e.remove(); removed++ }
        }
        return removed
    }

    override fun remove(handle: VisualHandle) {
        owners.remove(handle.id)
        when (val e = entries.remove(handle.id)) {
            is MeshEntry -> displays.remove(e.handle)
            is PlateEntry -> displays.remove(e.handle)
            is LineEntry -> displays.remove(e.handle)
            is TrailEntry -> {
                e.segments.forEach { server.getEntity(it.display)?.remove() }
                e.segments.clear()
            }
            null -> Unit
        }
    }

    // ---- geometry ----------------------------------------------------------------

    private fun lineTransform(from: Vec3, to: Vec3, thickness: Double, face: PlateFace, world: World): DisplayTransform =
        SkillDisplayMath.lineTransform(from, to, thickness, face, viewerEye(world, (from + to) * 0.5))

    private fun spawnSegment(t: TrailEntry, s: RibbonMesh.Segment, style: PieceStyle): UUID? {
        val m = SkillDisplayMath.ribbonColumns(s.u, s.v, s.normal)
        val matrix = Matrix4f(
            m.col0.x.toFloat(), m.col0.y.toFloat(), m.col0.z.toFloat(), 0f,
            m.col1.x.toFloat(), m.col1.y.toFloat(), m.col1.z.toFloat(), 0f,
            m.col2.x.toFloat(), m.col2.y.toFloat(), m.col2.z.toFloat(), 0f,
            m.shift.x.toFloat(), m.shift.y.toFloat(), m.shift.z.toFloat(), 1f,
        )
        val argb = colour(t, style, 0)
        val display = t.world.spawn(Location(t.world, s.centre.x, s.centre.y, s.centre.z), TextDisplay::class.java) {
            PaperManagedEntities.mark(it, "skill-ribbon")
            it.billboard = Display.Billboard.FIXED
            it.text(Component.text(" "))
            it.backgroundColor = argb
            it.textOpacity = 255.toByte()
            it.brightness = Display.Brightness(15, 15)
            it.shadowRadius = 0f
            it.setTransformationMatrix(matrix)
        }
        return display.uniqueId
    }

    /**
     * Quad colour at [age]: alpha `style.alpha * (1 - age/ticks) ^ fadePower`, the rgb blended toward `rgbTo` over the life.
     * A crisp quad (`crisp` / `both` bands, the edge) keeps full alpha until its last tick and then ends: its edge is the
     * point of the style, a fade would turn it soft.
     */
    private fun colour(t: TrailEntry, style: PieceStyle, age: Int): Color {
        val life = (1.0 - age.toDouble() / t.ticks).coerceIn(0.0, 1.0)
        val k = if (style.crisp && t.fadePower == 1.0) 1.0 else Math.pow(life, t.fadePower)
        val alpha = (style.alpha * k * 255.0).roundToInt().coerceIn(0, 255)
        val to = style.rgbTo
        val rgb = if (to == null) style.rgb else {
            val f = 1.0 - life
            fun ch(shift: Int): Int = (((style.rgb shr shift and 0xFF) * (1 - f)) + ((to shr shift and 0xFF) * f)).roundToInt().coerceIn(0, 255)
            (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return Color.fromARGB(alpha, rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
    }

    // ---- helpers -----------------------------------------------------------------

    /** TextDisplay quad maths (MC 26.3 one-line text centre, calibrated against a real-client capture). */
    private object Quad {
        const val MIN_TEXT_OPACITY: Int = 4
    }

    private fun uniform(scale: Double) = Vec3(scale, scale, scale)

    private fun opacity(alpha: Double): Int = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceAtLeast(Quad.MIN_TEXT_OPACITY)

    /** `#rrggbb` or `#rrggbbaa` (alpha last) to ARGB; a malformed value is white. */
    private fun argb(hex: String): Int {
        val h = hex.removePrefix("#")
        val rgb = h.take(6).toIntOrNull(16) ?: return 0xFFFFFFFF.toInt()
        val a = if (h.length >= 8) h.substring(6, 8).toIntOrNull(16) ?: 255 else 255
        return (a shl 24) or rgb
    }

    private fun worldOf(owner: UUID): World? {
        val world = server.getPlayer(owner)?.world ?: return null
        lastWorld = WorldId(world.name)
        return world
    }

    /** Eye of the nearest player within view distance, or null. */
    private fun viewerEye(world: World, at: Vec3): Vec3? {
        val near = world.getNearbyPlayers(Location(world, at.x, at.y, at.z), VIEWER_RADIUS)
            .minByOrNull { it.location.distanceSquared(Location(world, at.x, at.y, at.z)) } ?: return null
        val eye = near.eyeLocation
        return Vec3(eye.x, eye.y, eye.z)
    }

    private fun register(owner: UUID, entry: Entry): VisualHandle {
        val id = ids.getAndIncrement()
        entries[id] = entry
        owners[id] = owner
        return VisualHandle(id)
    }

    /** The cast owner is offline or the display could not spawn: a handle that every update ignores. */
    private fun dead(what: String): VisualHandle {
        log.warn("skill visual '{}' dropped: owner offline or display refused", what)
        return VisualHandle(ids.getAndIncrement())
    }

    private companion object {
        const val LIFETIME: Long = 1_000_000L
        const val SCALE_INTERPOLATION_TICKS: Int = 2
        const val VIEWER_RADIUS = 96.0
    }
}
