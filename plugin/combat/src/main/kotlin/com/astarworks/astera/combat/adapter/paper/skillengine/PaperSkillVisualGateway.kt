package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle
import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.effect.vfx.SpriteGlyphs
import com.astarworks.astera.core.domain.effect.vfx.SpriteId
import com.astarworks.astera.core.domain.effect.vfx.VfxQuad
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
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
 * Paper [ISkillVisualGateway] (ADR-0299). Meshes, plates and lines are Display entities from the shared
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
    /** Item model key of a mesh: item id and optional part (point-table part name). */
    private val modelKey: (item: String, part: String?) -> String = { item, part ->
        if (part == null) "astera:$item" else "astera:$item-${part.replace('_', '-')}"
    },
) : ISkillVisualGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val ids = AtomicLong(1)
    private val entries = ConcurrentHashMap<Long, Entry>()

    @Volatile
    private var lastWorld: WorldId? = null

    /** World of the most recent cast owner; the particle and sound ports carry no world (see report). */
    public fun activeWorld(): WorldId? = lastWorld

    private sealed interface Entry
    private class MeshEntry(val handle: DisplayHandle, var scale: Double, var rotation: Quaternion) : Entry
    private class PlateEntry(val handle: DisplayHandle, val face: PlateFace, var size: Double, val world: World, var rotation: Quaternion) : Entry
    private class LineEntry(val handle: DisplayHandle, val face: PlateFace, val thickness: Double, val world: World) : Entry
    private class Seg(val display: UUID, var age: Int)
    private class TrailEntry(
        val world: World, val ticks: Int, val widthFrac: Double, val rgb: Int, val alpha: Double,
    ) : Entry {
        val mesh = RibbonMesh()
        val segments = ArrayDeque<Seg>()
    }

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, scale: Double, pose: Frame): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("mesh $item")
        val spec = DisplaySpec(
            kind = DisplayKind.Item(ItemId("paper"), DisplayItemMode.FIXED, modelKey(item, part)),
            transform = DisplayTransform(Vec3.ZERO, pose.rotation, uniform(scale)),
            lifetimeTicks = LIFETIME,
            billboard = DisplayBillboard.FIXED,
            weaponRigShading = true,
        )
        val handle = displays.spawn(WorldId(world.name), pose.origin, spec, emptyList()) ?: return dead("mesh $item")
        return register(MeshEntry(handle, scale, pose.rotation))
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
            transform = plateTransform(placed.rotation, size),
            lifetimeTicks = LIFETIME,
            billboard = placed.billboard,
            brightness = 15,
        )
        val handle = displays.spawn(WorldId(world.name), pose.origin, spec, listOf(glyph)) ?: return dead("plate $sprite")
        return register(PlateEntry(handle, face, size, world, placed.rotation))
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
        return register(LineEntry(handle, face, thickness, world))
    }

    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("trail")
        val rgb = argb(colorHex) and 0xFFFFFF
        return register(TrailEntry(world, ticks.coerceAtLeast(1), widthFrac, rgb, alpha.coerceIn(0.0, 1.0)))
    }

    override fun updatePose(handle: VisualHandle, pose: Frame, interpolationTicks: Int) {
        when (val e = entries[handle.id]) {
            is MeshEntry -> {
                e.rotation = pose.rotation
                displays.teleport(e.handle, pose.origin, 0f, 0f, interpolationTicks)
                displays.update(e.handle, DisplayTransform(Vec3.ZERO, pose.rotation, uniform(e.scale)), interpolationTicks)
            }
            is PlateEntry -> {
                val placed = PlateOrientation.forPlate(pose, e.face, viewerEye(e.world, pose.origin))
                e.rotation = placed.rotation
                displays.teleport(e.handle, pose.origin, 0f, 0f, interpolationTicks)
                displays.update(e.handle, plateTransform(placed.rotation, e.size), interpolationTicks)
            }
            else -> Unit
        }
    }

    override fun updateScale(handle: VisualHandle, scale: Double) {
        when (val e = entries[handle.id]) {
            is MeshEntry -> {
                e.scale = scale
                displays.update(e.handle, DisplayTransform(Vec3.ZERO, e.rotation, uniform(scale)), SCALE_INTERPOLATION_TICKS)
            }
            is PlateEntry -> {
                e.size = scale
                displays.update(e.handle, plateTransform(e.rotation, scale), SCALE_INTERPOLATION_TICKS)
            }
            else -> Unit
        }
    }

    override fun updateLine(handle: VisualHandle, from: Vec3, to: Vec3) {
        val e = entries[handle.id] as? LineEntry ?: return
        displays.teleport(e.handle, (from + to) * 0.5, 0f, 0f, 1)
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
                (server.getEntity(seg.display) as? TextDisplay)?.backgroundColor = Color.fromARGB(fade(t, seg.age), t.rgb shr 16 and 0xFF, t.rgb shr 8 and 0xFF, t.rgb and 0xFF)
            }
        }
        val segment = t.mesh.push(shaped, tip) ?: return
        val id = spawnSegment(t, segment) ?: return
        t.segments.addLast(Seg(id, 0))
    }

    override fun remove(handle: VisualHandle) {
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

    private fun plateTransform(rotation: com.astarworks.astera.core.domain.geometry.math.Quaternion, size: Double): DisplayTransform {
        val s = size * VfxQuad.GLYPH_SCALE
        val centre = Vec3(-VfxQuad.CENTER_X_PER_SCALE * s, -VfxQuad.CENTER_Y_PER_SCALE * s, 0.0)
        return DisplayTransform(rotation.rotate(centre), rotation, Vec3(s, s, 1.0))
    }

    private fun lineTransform(from: Vec3, to: Vec3, thickness: Double, face: PlateFace, world: World): DisplayTransform {
        val rotation = PlateOrientation.forLine(from, to, face, viewerEye(world, (from + to) * 0.5))
        val sx = from.distance(to) * VfxQuad.SOLID_SCALE_X
        val sy = thickness * VfxQuad.SOLID_SCALE_Y
        val centre = Vec3(-VfxQuad.CENTER_X_PER_SCALE * sx, -VfxQuad.CENTER_Y_PER_SCALE * sy, 0.0)
        return DisplayTransform(rotation.rotate(centre), rotation, Vec3(sx, sy, 1.0))
    }

    private fun spawnSegment(t: TrailEntry, s: RibbonMesh.Segment): UUID? {
        val col0 = s.u * VfxQuad.SOLID_SCALE_X
        val col1 = s.v * VfxQuad.SOLID_SCALE_Y
        val shift = col0 * -VfxQuad.CENTER_X_PER_SCALE + col1 * -VfxQuad.CENTER_Y_PER_SCALE
        val matrix = Matrix4f(
            col0.x.toFloat(), col0.y.toFloat(), col0.z.toFloat(), 0f,
            col1.x.toFloat(), col1.y.toFloat(), col1.z.toFloat(), 0f,
            s.normal.x.toFloat(), s.normal.y.toFloat(), s.normal.z.toFloat(), 0f,
            shift.x.toFloat(), shift.y.toFloat(), shift.z.toFloat(), 1f,
        )
        val argb = Color.fromARGB(fade(t, 0), t.rgb shr 16 and 0xFF, t.rgb shr 8 and 0xFF, t.rgb and 0xFF)
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

    private fun fade(t: TrailEntry, age: Int): Int =
        (t.alpha * (1.0 - age.toDouble() / t.ticks) * 255.0).roundToInt().coerceIn(0, 255)

    // ---- helpers -----------------------------------------------------------------

    private fun uniform(scale: Double) = Vec3(scale, scale, scale)

    private fun opacity(alpha: Double): Int = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceAtLeast(VfxQuad.MIN_TEXT_OPACITY)

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

    private fun register(entry: Entry): VisualHandle {
        val id = ids.getAndIncrement()
        entries[id] = entry
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
