package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle
import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.skillengine.load.ItemFrames
import com.astarworks.astera.core.application.skillengine.load.MeshModel
import com.astarworks.astera.core.application.skillengine.load.MeshModels
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.vfx.VfxLane
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.sprite.SpriteGlyphs
import com.astarworks.astera.core.domain.sprite.SpriteId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayWrite
import com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.presentation.PresentationKind
import com.astarworks.astera.core.domain.presentation.PresentationLease
import com.astarworks.astera.core.domain.presentation.PresentationMask
import com.astarworks.astera.core.domain.player.PlayerId
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
 * [IDisplayEntityGateway]. Each tick a display gets ONE [IDisplayEntityGateway.place] write (location, full transformation,
 * one interpolation length, see [SkillDisplayWrites]); a display anchored to the caster rides the caster as a native passenger
 * (v1 ADR-0276 semantics) so it stays glued to the hand when the caster is moved, every other display is teleported. Trails are one fixed text-quad
 * Display per ribbon segment (a sheared parallelogram needs a transformation matrix, which the shared gateway does not
 * carry). Main thread only.
 *
 * Every mesh, plate and line write is [toDisplayWrite] of the pose the runtime computed from the frame tree; this class adds
 * no maths of its own. Viewer-facing plates use the vanilla client billboard.
 */
public class PaperSkillVisualGateway(
    private val server: Server,
    private val displays: IDisplayEntityGateway,
    /** Item point tables: a mesh part names its pack model there ([MeshModels]). */
    private val frameTables: Map<String, ItemFrames> = emptyMap(),
    /** Flipbook length and cell size of a sprite (`content/sprites/<id>.yaml`); 1 frame, 32 px when unknown. */
    private val spriteInfo: (String) -> SpriteInfo = { SpriteInfo(1, 32) },
    /** Hides the real held item while a `main_hand` mesh stands in for it (ADR-0276: one weapon on screen, never two). */
    private val presentation: () -> IPlayerPresentation? = { null },
) : ISkillVisualGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val ids = AtomicLong(1)
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val owners = ConcurrentHashMap<Long, UUID>()

    private sealed interface Entry
    private class MeshEntry(val handle: DisplayHandle, val owner: UUID, val heldItem: Boolean = false) : Entry
    /** One hand lease per caster, shared by every live `main_hand` mesh of that caster. */
    private class HandHide(val lease: PresentationLease) { var users: Int = 0 }
    private val handHides = ConcurrentHashMap<UUID, HandHide>()
    private class SkinEntry(val handle: DisplayHandle, val owner: UUID, val ghost: Boolean) : Entry
    /** One body lease per caster, shared by every live non-ghost skin bone of that caster (hand, offhand, armour, body hidden, pose pinned). */
    private val bodyHides = ConcurrentHashMap<UUID, HandHide>()
    private class PlateEntry(val handle: DisplayHandle, val owner: UUID, val sprite: String, val intrinsic: Intrinsic) : Entry
    private class LineEntry(val handle: DisplayHandle) : Entry
    private class BlockEntry(val handle: DisplayHandle, val owner: UUID) : Entry
    private class QuadEntry(val handle: DisplayHandle, val owner: UUID, val intrinsic: Intrinsic, var colorHex: String, var alpha: Double) : Entry
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

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose, brightness: Int?, look: MeshLook): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("mesh $item")
        val heldItem = SkillDisplaySpecs.meshKind(MeshModels.resolve(item, part, frameTables), look) == null
        val kind = SkillDisplaySpecs.meshKind(MeshModels.resolve(item, part, frameTables), look) ?: run {
            val held = server.getPlayer(castOwner)?.inventory?.itemInMainHand?.takeUnless { it.type.isAir }
                ?: return dead("mesh $item (empty main hand)")
            DisplayKind.Item(ItemId(held.type.key.key.replace('_', '-')), look.mode, held.itemMeta?.takeIf { it.hasItemModel() }?.itemModel?.toString())
        }
        val first = toDisplayWrite(pose.frame, pose.size, Intrinsic.ITEM_FIXED, pose.billboard, 0)
        val spec = SkillDisplaySpecs.mesh(kind, first.transform, brightness, look.rigShading)
        val handle = spawnDisplay(world, castOwner, pose.frame.origin, spec, emptyList(), pose.anchorFeet != null, pose.lane) ?: return dead("mesh $item")
        if (heldItem) hideHand(castOwner)
        return register(castOwner, MeshEntry(handle, castOwner, heldItem))
    }

    override fun spawnSkinBone(castOwner: UUID, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("skin bone ${bone.yamlName}")
        val first = toDisplayWrite(pose.frame, pose.size, Intrinsic.ITEM_FIXED, pose.billboard, 0)
        val spec = SkillDisplaySpecs.skinBone(DisplayKind.SkinBone(bone, slim, ghost), first.transform)
        val handle = spawnDisplay(world, castOwner, pose.frame.origin, spec, emptyList(), pose.anchorFeet != null) ?: return dead("skin bone ${bone.yamlName}")
        if (!ghost) hideBody(castOwner)
        return register(castOwner, SkinEntry(handle, castOwner, ghost))
    }

    private fun hideBody(owner: UUID) {
        bodyHides[owner]?.let { it.users++; return }
        val port = presentation() ?: return
        val lease = when (val claimed = port.claim(PlayerId(owner), BODY, PresentationKind.PERFORMANCE, onRevoked = { bodyHides.remove(owner) })) {
            is Result.Success -> claimed.value
            is Result.Failure -> return
        }
        port.reveal(lease)
        bodyHides[owner] = HandHide(lease).also { it.users = 1 }
    }

    private fun showBody(owner: UUID) {
        val hide = bodyHides[owner] ?: return
        if (--hide.users > 0) return
        bodyHides.remove(owner)
        presentation()?.release(hide.lease)
    }

    private fun hideHand(owner: UUID) {
        handHides[owner]?.let { it.users++; return }
        val port = presentation() ?: return
        val lease = when (val claimed = port.claim(PlayerId(owner), HAND, PresentationKind.TIMED, onRevoked = { handHides.remove(owner) })) {
            is Result.Success -> claimed.value
            is Result.Failure -> return
        }
        port.reveal(lease)
        handHides[owner] = HandHide(lease).also { it.users = 1 }
    }

    private fun showHand(owner: UUID) {
        val hide = handHides[owner] ?: return
        if (--hide.users > 0) return
        handHides.remove(owner)
        presentation()?.release(hide.lease)
    }

    override fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, pose: VisualPose, brightness: Int?): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("plate $sprite")
        val glyph = SpriteGlyphs.text(SpriteId(sprite), 0)
        val intrinsic = Intrinsic.textGlyph(spriteInfo(sprite).px)
        val first = toDisplayWrite(pose.frame, pose.size, intrinsic, pose.billboard, 0)
        val spec = SkillDisplaySpecs.plate(glyph, alpha, first.transform, pose.billboard, brightness ?: 15)
        val handle = spawnDisplay(world, castOwner, pose.frame.origin, spec, listOf(glyph), pose.anchorFeet != null && pose.billboard == DisplayBillboard.FIXED, pose.lane)
            ?: return dead("plate $sprite")
        return register(castOwner, PlateEntry(handle, castOwner, sprite, intrinsic))
    }

    override fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("block $block")
        val first = toDisplayWrite(pose.frame, pose.size, Intrinsic.BLOCK, DisplayBillboard.FIXED, 0)
        val spec = SkillDisplaySpecs.block(block, first.transform, brightness, rigShading)
        val handle = spawnDisplay(world, castOwner, pose.frame.origin, spec, emptyList(), pose.anchorFeet != null, pose.lane) ?: return dead("block $block")
        return register(castOwner, BlockEntry(handle, castOwner))
    }

    override fun spawnQuad(castOwner: UUID, colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("quad")
        val intrinsic = if (text == null) Intrinsic.TEXT_SOLID else Intrinsic.QUAD_GLYPH
        val first = toDisplayWrite(pose.frame, pose.size, intrinsic, pose.billboard, 0)
        val spec = SkillDisplaySpecs.quad(colorHex, alpha, text, shadow, seeThrough, first.transform, pose.billboard, brightness)
        val handle = spawnDisplay(world, castOwner, pose.frame.origin, spec, listOf(text ?: " "), pose.anchorFeet != null && pose.billboard == DisplayBillboard.FIXED, pose.lane)
            ?: return dead("quad")
        return register(castOwner, QuadEntry(handle, castOwner, intrinsic, colorHex, alpha))
    }

    override fun spawnLine(castOwner: UUID, colorHex: String, pose: VisualPose): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("line")
        val first = toDisplayWrite(pose.frame, pose.size, Intrinsic.TEXT_SOLID, DisplayBillboard.FIXED, 0)
        val spec = SkillDisplaySpecs.line(colorHex, first.transform)
        val handle = displays.spawn(WorldId(world.name), pose.frame.origin, spec, listOf(" ")) ?: return dead("line")
        return register(castOwner, LineEntry(handle))
    }

    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle {
        val world = worldOf(castOwner) ?: return dead("trail")
        val rgb = argb(colorHex) and 0xFFFFFF
        return register(castOwner, TrailEntry(world, ticks.coerceAtLeast(1), widthFrac, rgb, alpha.coerceIn(0.0, 1.0)))
    }

    override fun sprite(sprite: String): SpriteInfo = spriteInfo(sprite).let { SpriteInfo(it.frames.coerceAtLeast(1), it.px.coerceAtLeast(1)) }

    override fun setPlateFrame(handle: VisualHandle, frame: Int, tintHex: String?) {
        val e = entries[handle.id] as? PlateEntry ?: return
        val rgb = tintHex?.let { argb(it) and 0xFFFFFF }
        displays.setText(e.handle, listOf(SpriteGlyphs.text(SpriteId(e.sprite), frame, rgb)))
    }

    override fun setLook(handle: VisualHandle, alpha: Double?, colorHex: String?) {
        when (val e = entries[handle.id]) {
            is PlateEntry -> if (alpha != null) displays.setTextStyle(e.handle, 0, SkillDisplaySpecs.opacity(alpha), null)
            is QuadEntry -> {
                alpha?.let { e.alpha = it }
                colorHex?.let { e.colorHex = it }
                val a = (e.alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt()
                displays.setTextStyle(e.handle, (a shl 24) or (argb(e.colorHex) and 0xFFFFFF), 255, null)
            }
            else -> {}
        }
    }

    override fun setBlock(handle: VisualHandle, block: String) {
        val e = entries[handle.id] as? BlockEntry ?: return
        displays.setBlock(e.handle, block)
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

    override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) {
        when (val e = entries[handle.id]) {
            is MeshEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, Intrinsic.ITEM_FIXED, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is SkinEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, Intrinsic.ITEM_FIXED, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is PlateEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, e.intrinsic, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is BlockEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, Intrinsic.BLOCK, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is QuadEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, e.intrinsic, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is LineEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, Intrinsic.TEXT_SOLID, DisplayBillboard.FIXED, interpolationTicks))
            is TrailEntry -> error("a trail is fed through pushTrailPoint, never written")
            null -> Unit // already removed (owner quit / cast ended): nothing left to move
        }
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
            is MeshEntry -> { displays.remove(e.handle); if (e.heldItem) showHand(e.owner) }
            is SkinEntry -> { displays.remove(e.handle); if (!e.ghost) showBody(e.owner) }
            is PlateEntry -> displays.remove(e.handle)
            is BlockEntry -> displays.remove(e.handle)
            is QuadEntry -> displays.remove(e.handle)
            is LineEntry -> displays.remove(e.handle)
            is TrailEntry -> {
                e.segments.forEach { server.getEntity(it.display)?.remove() }
                e.segments.clear()
            }
            null -> Unit
        }
    }

    // ---- geometry ----------------------------------------------------------------

    private fun spawnSegment(t: TrailEntry, s: RibbonMesh.Segment, style: PieceStyle): UUID? {
        val m = ribbonColumns(s.u, s.v, s.normal)
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

    private fun argb(hex: String): Int = SkillDisplaySpecs.argb(hex)

    private companion object {
        val HAND = PresentationMask(hand = true)
        val BODY = PresentationMask(hand = true, offhand = true, armor = true, body = true, pinPose = true)

        val BODY = PresentationMask(hand = true, offhand = true, armor = true, body = true, pinPose = true)
    }

    private fun worldOf(owner: UUID): World? {
        val world = server.getPlayer(owner)?.world ?: return null
        return world
    }

     /** The caster as the anchor of a display, or null when it is not anchored (or the caster is offline). */
    private fun anchorOf(owner: UUID, feet: Vec3?): DisplayWrite.Anchor? =
        if (feet != null && server.getPlayer(owner) != null) DisplayWrite.Anchor(PlayerId(owner), feet) else null

    /** Spawns a display; an anchored one is born riding the caster (no frame at the wrong place), with the plain spawn as fallback. */
    private fun spawnDisplay(world: World, owner: UUID, at: Vec3, spec: DisplaySpec, lines: List<String>, anchored: Boolean, lane: VfxLane = VfxLane.CORE): DisplayHandle? {
        val player = if (anchored) server.getPlayer(owner) else null
        if (player != null) {
            val feet = player.location
            displays.spawnAttached(WorldId(world.name), spec, lines, PlayerId(owner), at - Vec3(feet.x, feet.y, feet.z), lane)?.let { return it }
        }
        return displays.spawnLaned(WorldId(world.name), at, spec, lines, lane, PlayerId(owner))
    }

    private fun register(owner: UUID, entry: Entry): VisualHandle {
        val id = ids.getAndIncrement()
        entries[id] = entry
        owners[id] = owner
        return VisualHandle(id)
    }

    /** The cast owner is offline or the display could not spawn: an error the op runner reports (never a silent dead handle). */
    private fun dead(what: String): Nothing = error("skill visual '$what' could not be spawned: owner offline or display refused")
}
