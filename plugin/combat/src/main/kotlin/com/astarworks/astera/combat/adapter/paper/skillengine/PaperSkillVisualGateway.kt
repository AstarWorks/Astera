package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayScope
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle
import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.skillengine.load.ItemFrames
import com.astarworks.astera.core.application.skillengine.load.MeshModel
import com.astarworks.astera.core.application.skillengine.load.MeshModels
import com.astarworks.astera.core.application.skillengine.runtime.port.LineGeometry
import com.astarworks.astera.core.application.skillengine.runtime.port.LineSegmentFrame
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.VfxLane
import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.ScreenVeilMath
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.sprite.SpriteGlyphs
import com.astarworks.astera.core.domain.sprite.SpriteId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayWrite
import com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation
import com.astarworks.astera.core.domain.Result
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
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import net.kyori.adventure.key.Key
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
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
 * (ADR-0276 semantics) so it stays glued to the hand when the caster is moved, every other display is teleported. Trails are one fixed text-quad
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
    private class PlateEntry(val handle: DisplayHandle, val owner: UUID, val sprite: String, val intrinsic: Intrinsic, var rgb: Int?, var opacity: Int) : Entry {
        var frame: Int = 0
    }
    private class BlockEntry(val handle: DisplayHandle, val owner: UUID) : Entry
    private class TextEntry(val handle: DisplayHandle, val owner: UUID, val intrinsic: Intrinsic, val backgroundArgb: Int) : Entry {
        var opacity: Int = 255
    }
    /** Colour of one soft ribbon quad: peak [alpha] and the [rgbTo] it has faded to at the end of its life. */
    private class PieceStyle(val rgb: Int, val alpha: Double, val rgbTo: Int?)
    /** A block slab of a ribbon: its [axes], whether it is the [edge] (thins out) and its [thickness]. */
    private class Slab(val axes: RibbonMesh.SlabAxes, val edge: Boolean, val thickness: Double)
    private class Seg(val display: UUID, var age: Int, val style: PieceStyle?, val slab: Slab?)
    /** A thread between two points: one text-display strip that the runtime re-ends every tick. */
    private class LineEntry(val world: World, val owner: UUID, val displays: List<ItemDisplay>, val thickness: Double) : Entry
    /** A camera-anchored quad on one viewer's screen; re-placed in front of the eye every tick. */
    private class VeilEntry(val handle: DisplayHandle, val viewer: UUID, val distance: Double, val side: Double, val ticks: Int) : Entry

    private class TrailEntry(
        val world: World, val ticks: Int, var widthFrac: Double, val rgb: Int, val alpha: Double,
    ) : Entry {
        var fadePower: Double = 1.0
        var style: String = "soft"
        var bands: List<TrailBand> = emptyList()
        var edge: TrailEdge? = null
        var rim: TrailRim? = null
        var stepDeg: Double = 30.0
        var doubleSided: Boolean = false
        val mesh = RibbonMesh()
        val segments = ArrayDeque<Seg>()
    }

    override fun spawnMesh(cast: CastRef, item: String, part: String?, pose: VisualPose, brightness: Int?, look: MeshLook, view: DisplayView, glow: String?): VisualHandle {
        val castOwner = cast.caster
        val world = worldOf(cast) ?: return dead("mesh $item")
        val model = MeshModels.resolve(item, part, frameTables)
        // A main-hand mesh draws the model of the item the cast was started with (CastRef.heldItem, fixed at cast start), drawn in
        // THIRDPERSON_RIGHTHAND; the live main hand is never read, so an empty or swapped hand cannot abort the cast.
        val heldItem = model == MeshModel.HeldItem
        if (heldItem && cast.heldItem == null) return dead("mesh $item (the cast carries no item)")
        val kind = SkillDisplaySpecs.meshKind(model, look, cast.heldItem)
        val first = toDisplayWrite(pose.frame, pose.size, Intrinsic.ITEM_FIXED, pose.billboard, 0)
        val spec = SkillDisplaySpecs.mesh(kind, first.transform, brightness, look.rigShading, look.shaderEffect).copy(glowArgb = SkillDisplaySpecs.glowArgb(glow))
        val handle = spawnDisplay(world, castOwner, pose.frame.origin, spec, emptyList(), pose.anchorFeet != null, view) ?: return dead("mesh $item")
        if (heldItem) hideHand(castOwner)
        return register(castOwner, MeshEntry(handle, castOwner, heldItem))
    }

    override fun spawnSkinBone(cast: CastRef, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose): VisualHandle {
        val castOwner = cast.caster
        val world = worldOf(cast) ?: return dead("skin bone ${bone.yamlName}")
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

    override fun spawnPlate(cast: CastRef, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?): VisualHandle {
        val castOwner = cast.caster
        val world = worldOf(cast) ?: return dead("plate $sprite")
        val glyph = SpriteGlyphs.text(SpriteId(sprite), 0, tintHex?.let { argb(it) and 0xFFFFFF })
        val intrinsic = Intrinsic.SPRITE
        // a `#aarrggbb` tint carries its own opacity (the flat translucent quad of ADR-0303 2.1)
        val shown = alpha * (tintHex?.let { SkillDisplaySpecs.alphaOf(it) } ?: 1.0)
        val first = toDisplayWrite(pose.frame, pose.size, intrinsic, pose.billboard, 0)
        val spec = SkillDisplaySpecs.plate(glyph, shown, seeThrough, first.transform, pose.billboard, brightness ?: 15).copy(glowArgb = SkillDisplaySpecs.glowArgb(glow))
        val handle = spawnDisplay(world, castOwner, pose.frame.origin, spec, listOf(glyph), pose.anchorFeet != null && pose.billboard == DisplayBillboard.FIXED, view)
            ?: return dead("plate $sprite")
        return register(castOwner, PlateEntry(handle, castOwner, sprite, intrinsic, tintHex?.let { argb(it) and 0xFFFFFF }, SkillDisplaySpecs.opacity(shown)))
    }

    override fun spawnBlock(cast: CastRef, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView, glow: String?): VisualHandle {
        val castOwner = cast.caster
        val world = worldOf(cast) ?: return dead("block $block")
        val first = toDisplayWrite(pose.frame, pose.size, Intrinsic.BLOCK, DisplayBillboard.FIXED, 0)
        val spec = SkillDisplaySpecs.block(block, first.transform, brightness, rigShading).copy(glowArgb = SkillDisplaySpecs.glowArgb(glow))
        val handle = spawnDisplay(world, castOwner, pose.frame.origin, spec, emptyList(), pose.anchorFeet != null, view) ?: return dead("block $block")
        return register(castOwner, BlockEntry(handle, castOwner))
    }

    override fun spawnText(cast: CastRef, text: String, backgroundArgbHex: String, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView): VisualHandle {
        val castOwner = cast.caster
        val world = worldOf(cast) ?: return dead("text")
        val intrinsic = Intrinsic.TEXT_QUAD
        val first = toDisplayWrite(pose.frame, pose.size, intrinsic, pose.billboard, 0)
        val spec = SkillDisplaySpecs.text(text, backgroundArgbHex, shadow, seeThrough, first.transform, pose.billboard, brightness)
        val handle = spawnDisplay(world, castOwner, pose.frame.origin, spec, listOf(text), pose.anchorFeet != null && pose.billboard == DisplayBillboard.FIXED, view)
            ?: return dead("text")
        return register(castOwner, TextEntry(handle, castOwner, intrinsic, argb(backgroundArgbHex)))
    }

    override fun spawnTrail(cast: CastRef, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle {
        val castOwner = cast.caster
        val world = worldOf(cast) ?: return dead("trail")
        val rgb = argb(colorHex) and 0xFFFFFF
        return register(castOwner, TrailEntry(world, ticks.coerceAtLeast(1), widthFrac, rgb, alpha.coerceIn(0.0, 1.0)))
    }

    override fun spawnLine(cast: CastRef, vertices: List<Vec3>, thickness: Double, colorHex: String, alpha: Double): VisualHandle {
        require(vertices.size >= 2) { "a line needs at least two vertices" }
        val castOwner = cast.caster
        val world = worldOf(cast) ?: return dead("line")
        val stack = lineStack(argb(colorHex) and 0xFFFFFF, alpha)
        // a jagged bolt (more than one segment) re-jags as a cut: no slide between two shapes; a straight thread keeps its one-tick glide
        val glide = if (vertices.size > 2) 0 else 1
        val displays = ArrayList<ItemDisplay>(vertices.size - 1)
        for (s in 0 until vertices.size - 1) {
            val mid = (vertices[s] + vertices[s + 1]) * 0.5
            val frame = LineGeometry.frame(vertices[s], vertices[s + 1], thickness)
            displays += world.spawn(Location(world, mid.x, mid.y, mid.z), ItemDisplay::class.java) {
                PaperManagedEntities.mark(it, "skill-ribbon")
                it.billboard = Display.Billboard.FIXED
                it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                it.setItemStack(stack.clone())
                it.brightness = LINE_BRIGHTNESS
                it.shadowRadius = 0f
                it.teleportDuration = glide
                it.interpolationDuration = glide
                it.setTransformationMatrix(segmentMatrix(frame))
            }
        }
        return register(castOwner, LineEntry(world, castOwner, displays, thickness))
    }

    override fun updateLine(handle: VisualHandle, vertices: List<Vec3>) {
        val e = entries[handle.id] as? LineEntry ?: return
        if (vertices.size != e.displays.size + 1) return
        for (s in 0 until vertices.size - 1) {
            val mid = (vertices[s] + vertices[s + 1]) * 0.5
            val d = e.displays[s]
            d.teleportAsync(Location(e.world, mid.x, mid.y, mid.z))
            d.interpolationDelay = 0
            d.setTransformationMatrix(segmentMatrix(LineGeometry.frame(vertices[s], vertices[s + 1], e.thickness)))
        }
    }

    override fun spawnScreenVeil(cast: CastRef, viewer: UUID, sprite: String, distance: Double, scale: Double, colorHex: String?, maxScreenFraction: Double, ticks: Int): VisualHandle {
        val castOwner = cast.caster
        val player = server.getPlayer(viewer) ?: return dead("screen_veil $sprite (viewer offline)")
        val world = worldOf(cast) ?: return dead("screen_veil $sprite")
        val glyph = SpriteGlyphs.text(SpriteId(sprite), 0, colorHex?.let { argb(it) and 0xFFFFFF })
        // Screen veil: side = SPRITE_BASE_SIDE * scale, never more than the screen share the content allows at this distance
        val side = ScreenVeilMath.clampSide(ScreenVeilMath.SPRITE_BASE_SIDE * scale, distance, maxScreenFraction)
        val eye = player.eyeLocation
        val at = Vec3(eye.x, eye.y, eye.z) + eyeDirection(player) * distance
        val first = toDisplayWrite(Frame(at, Quaternion.IDENTITY), Vec3(side, side, side), Intrinsic.SPRITE, DisplayBillboard.CENTER, 0)
        val spec = SkillDisplaySpecs.plate(glyph, 1.0, false, first.transform, DisplayBillboard.CENTER, 15)
        val handle = spawnDisplay(world, castOwner, at, spec, listOf(glyph), false, DisplayView(Audience.TARGET, VfxLane.ACCENT, viewer))
            ?: return dead("screen_veil $sprite")
        return register(castOwner, VeilEntry(handle, viewer, distance, side, ticks))
    }

    override fun updateScreenVeil(handle: VisualHandle, age: Int) {
        val e = entries[handle.id] as? VeilEntry ?: return
        val player = server.getPlayer(e.viewer) ?: return remove(handle)
        val eye = player.eyeLocation
        val at = Vec3(eye.x, eye.y, eye.z) + eyeDirection(player) * e.distance
        displays.place(e.handle, toDisplayWrite(Frame(at, Quaternion.IDENTITY), Vec3(e.side, e.side, e.side), Intrinsic.SPRITE, DisplayBillboard.CENTER, 0, teleportTicks = 0))
        val opacity = SkillDisplaySpecs.opacity((1.0 - age.toDouble() / e.ticks).coerceIn(0.0, 1.0))
        displays.setTextStyle(e.handle, 0, opacity, null)
    }

    private fun eyeDirection(player: org.bukkit.entity.Player): Vec3 = player.eyeLocation.direction.let { Vec3(it.x, it.y, it.z) }

    /**
     * The item of one thread segment (ADR-0303 rev4.15): model `astera:skill_line`, tint = [rgb], opacity = the texture step `a01..a15`
     * (`strings[0]`) nearest [alpha]. Both are fixed at spawn, as a line's colour and alpha always were.
     */
    private fun lineStack(rgb: Int, alpha: Double): ItemStack {
        val step = (alpha.coerceIn(0.0, 1.0) * LINE_ALPHA_STEPS).roundToInt().coerceIn(1, LINE_ALPHA_STEPS)
        val stack = ItemStack(Material.PAPER)
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("astera", "skill_line"))
        stack.setData(
            DataComponentTypes.CUSTOM_MODEL_DATA,
            CustomModelData.customModelData().addColor(Color.fromRGB(rgb)).addString("a%02d".format(step)).build(),
        )
        return stack
    }

    /** One segment as a display transform: columns [LineSegmentFrame.along], [LineSegmentFrame.across], [LineSegmentFrame.normal]; the model is centred, so no translation. */
    private fun segmentMatrix(frame: LineSegmentFrame): Matrix4f = Matrix4f(
        frame.along.x.toFloat(), frame.along.y.toFloat(), frame.along.z.toFloat(), 0f,
        frame.across.x.toFloat(), frame.across.y.toFloat(), frame.across.z.toFloat(), 0f,
        frame.normal.x.toFloat(), frame.normal.y.toFloat(), frame.normal.z.toFloat(), 0f,
        0f, 0f, 0f, 1f,
    )

    /**
     * `set text_opacity` for a text or sprite display (`value` 0..1). The client does not interpolate it, so the table
     * writes one set per changed tick. false = the handle is not a text / sprite display.
     */
    public fun setOpacity(handle: VisualHandle, value: Double): Boolean {
        when (val e = entries[handle.id]) {
            is PlateEntry -> { e.opacity = SkillDisplaySpecs.opacity(value); displays.setTextStyle(e.handle, 0, e.opacity, null) }
            is TextEntry -> { e.opacity = SkillDisplaySpecs.opacity(value); displays.setTextStyle(e.handle, e.backgroundArgb, e.opacity, null) }
            else -> return false
        }
        return true
    }

    /**
     * `set color` (`#rrggbb`) and `set text` (a sprite flipbook glyph, see [SpriteGlyphs.text]) for a text or sprite display.
     * A plate re-renders its current frame with the current tint; a text display restyles its colour / swaps its line.
     * false = the handle is not a text / sprite display or the field is not one of those.
     */
    public fun setText(handle: VisualHandle, field: String, value: String): Boolean {
        when (val e = entries[handle.id]) {
            is PlateEntry -> when (field) {
                "color" -> {
                    e.rgb = argb(value) and 0xFFFFFF
                    displays.setText(e.handle, listOf(SpriteGlyphs.text(SpriteId(e.sprite), e.frame, e.rgb)))
                    if (value.removePrefix("#").length == 8) { e.opacity = SkillDisplaySpecs.opacity(SkillDisplaySpecs.alphaOf(value)); displays.setTextStyle(e.handle, 0, e.opacity, null) }
                }
                "text" -> {
                    val glyph = value.firstOrNull { it.code in 0xE000 until 0xE000 + SpriteGlyphs.MAX_FRAMES } ?: return false
                    e.frame = glyph.code - 0xE000
                    displays.setText(e.handle, listOf(SpriteGlyphs.text(SpriteId(e.sprite), e.frame, e.rgb)))
                }
                else -> return false
            }
            is TextEntry -> when (field) {
                "color" -> displays.setTextStyle(e.handle, e.backgroundArgb, e.opacity, argb(value) and 0xFFFFFF)
                "text" -> displays.setText(e.handle, listOf(value))
                else -> return false
            }
            else -> return false
        }
        return true
    }

    override fun sprite(sprite: String): SpriteInfo = spriteInfo(sprite).let { SpriteInfo(it.frames.coerceAtLeast(1), it.px.coerceAtLeast(1)) }

    override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) {
        val t = entries[handle.id] as? TrailEntry ?: return
        t.fadePower = fadePower.coerceAtLeast(0.0)
        t.style = style
    }

    override fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) {
        val t = entries[handle.id] as? TrailEntry ?: return
        t.bands = bands
        t.edge = edge
        t.rim = rim
        t.stepDeg = stepDeg
        t.doubleSided = doubleSided
    }

    override fun setTrailWidth(handle: VisualHandle, widthFrac: Double) {
        val t = entries[handle.id] as? TrailEntry ?: return
        t.widthFrac = widthFrac.coerceAtLeast(0.0)
    }

    override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) {
        when (val e = entries[handle.id]) {
            is MeshEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, Intrinsic.ITEM_FIXED, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is SkinEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, Intrinsic.ITEM_FIXED, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is PlateEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, e.intrinsic, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is BlockEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, Intrinsic.BLOCK, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is TextEntry -> displays.place(e.handle, toDisplayWrite(pose.frame, pose.size, e.intrinsic, pose.billboard, interpolationTicks, anchorOf(e.owner, pose.anchorFeet), pose.teleportTicks))
            is TrailEntry -> error("a trail is fed through pushTrailPoint, never written")
            is LineEntry -> error("a line is moved through updateLine, never written")
            is VeilEntry -> error("a screen veil is moved through updateScreenVeil, never written")
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
            } else if (seg.slab != null) {
                (server.getEntity(seg.display) as? BlockDisplay)?.setTransformationMatrix(slabMatrix(seg.slab, t, seg.age))
            } else {
                (server.getEntity(seg.display) as? TextDisplay)?.backgroundColor = colour(t, seg.style!!, seg.age)
            }
        }
        val bands = if (t.bands.isEmpty()) listOf(RibbonMesh.Band(0.0, 1.0)) else t.bands.map { RibbonMesh.Band(it.from, it.to) }
        val pieces = t.mesh.pushShaped(shaped, tip, bands, t.edge?.width, t.rim?.width, t.stepDeg)
        for (d in RibbonMesh.drawn(pieces, t.style, t.bands, t.edge, t.doubleSided)) {
            val seg = d.piece.segment
            if (d.draw == RibbonMesh.Draw.SLAB) {
                val axes = RibbonMesh.slabAxes(seg) ?: continue
                val slab = Slab(axes, d.piece.kind == RibbonMesh.PieceKind.EDGE, if (d.piece.kind == RibbonMesh.PieceKind.EDGE) t.edge!!.width else RibbonMesh.SLAB_THICKNESS)
                spawnSlab(t, seg, d.block!!, slab)?.let { t.segments.addLast(Seg(it, 0, null, slab)) }
            } else {
                val style = styleOf(t, d.piece)
                val face = if (d.back) RibbonMesh.Segment(seg.centre, seg.u * -1.0, seg.v, seg.normal * -1.0) else seg
                spawnSegment(t, face, style)?.let { t.segments.addLast(Seg(it, 0, style, null)) }
            }
        }
    }

    private fun styleOf(t: TrailEntry, piece: RibbonMesh.Piece): PieceStyle = when (piece.kind) {
        RibbonMesh.PieceKind.BAND ->
            if (t.bands.isEmpty()) PieceStyle(t.rgb, t.alpha, null)
            else t.bands[piece.index].let { PieceStyle(argb(it.color) and 0xFFFFFF, it.alpha, it.colorTo?.let { c -> argb(c) and 0xFFFFFF }) }
        RibbonMesh.PieceKind.RIM -> PieceStyle(argb(t.rim!!.color) and 0xFFFFFF, t.rim!!.alpha, null)
        RibbonMesh.PieceKind.EDGE -> error("an edge is a block slab, never a soft quad")
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
            is TextEntry -> displays.remove(e.handle)
            is LineEntry -> e.displays.forEach { it.remove() }
            is VeilEntry -> displays.remove(e.handle)
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

    /** One block slab: its box sits on [s] and thins out with the fade factor. */
    private fun spawnSlab(t: TrailEntry, s: RibbonMesh.Segment, block: String, slab: Slab): UUID? {
        val display = t.world.spawn(Location(t.world, s.centre.x, s.centre.y, s.centre.z), BlockDisplay::class.java) {
            PaperManagedEntities.mark(it, "skill-ribbon")
            it.billboard = Display.Billboard.FIXED
            it.block = server.createBlockData("minecraft:$block")
            it.brightness = Display.Brightness(15, 15)
            it.setTransformationMatrix(slabMatrix(slab, t, 0))
        }
        return display.uniqueId
    }

    private fun slabMatrix(slab: Slab, t: TrailEntry, age: Int): Matrix4f {
        val life = (1.0 - age.toDouble() / t.ticks).coerceIn(0.0, 1.0)
        val size = RibbonMesh.slabSize(slab.axes, slab.edge, slab.thickness, Math.pow(life, t.fadePower))
        val c0 = slab.axes.ux * size.x
        val c1 = slab.axes.cy * size.y
        val c2 = slab.axes.n * size.z
        val shift = (c0 + c1 + c2) * -0.5
        return Matrix4f(
            c0.x.toFloat(), c0.y.toFloat(), c0.z.toFloat(), 0f,
            c1.x.toFloat(), c1.y.toFloat(), c1.z.toFloat(), 0f,
            c2.x.toFloat(), c2.y.toFloat(), c2.z.toFloat(), 0f,
            shift.x.toFloat(), shift.y.toFloat(), shift.z.toFloat(), 1f,
        )
    }

    /** Soft quad colour at [age]: alpha `style.alpha * (1 - age/ticks) ^ fadePower`, the rgb blended toward `rgbTo` over the life. */
    private fun colour(t: TrailEntry, style: PieceStyle, age: Int): Color {
        val life = (1.0 - age.toDouble() / t.ticks).coerceIn(0.0, 1.0)
        val k = Math.pow(life, t.fadePower)
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
        /** Brightness(12, 15) = UV2 (192, 240): the marker of the ADR-0305 shader effect `skill-line`. */
        val LINE_BRIGHTNESS = Display.Brightness(12, 15)

        /** Texture steps `a01..a15` of the `astera:skill_line` model (ResourcePackBuilder.LINE_ALPHA_STEPS). */
        const val LINE_ALPHA_STEPS = 15

        val HAND = PresentationMask(hand = true)

        val BODY = PresentationMask(hand = true, offhand = true, armor = true, body = true, pinPose = true)
    }

    private fun worldOf(cast: CastRef): World? = server.getWorld(cast.world.value)

     /** The caster as the anchor of a display, or null when it is not anchored (or the caster is offline). */
    private fun anchorOf(owner: UUID, feet: Vec3?): DisplayWrite.Anchor? =
        if (feet != null && server.getPlayer(owner) != null) DisplayWrite.Anchor(PlayerId(owner), feet) else null

    /** Spawns a display; an anchored one is born riding the caster (no frame at the wrong place), with the plain spawn as fallback. */
    private fun spawnDisplay(world: World, owner: UUID, at: Vec3, spec: DisplaySpec, lines: List<String>, anchored: Boolean, view: DisplayView = DisplayView()): DisplayHandle? {
        val player = if (anchored) server.getPlayer(owner) else null
        val caster = PlayerId(owner)
        val target = view.target?.let(::PlayerId)
        if (player != null) {
            val feet = player.location
            val scope = DisplayScope(view.audience, caster, target, view.lane)
            displays.spawnAttached(WorldId(world.name), spec, lines, caster, at - Vec3(feet.x, feet.y, feet.z), scope)?.let { return it }
        }
        return displays.spawn(WorldId(world.name), at, spec, lines, view.audience, caster, target, view.lane)
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
