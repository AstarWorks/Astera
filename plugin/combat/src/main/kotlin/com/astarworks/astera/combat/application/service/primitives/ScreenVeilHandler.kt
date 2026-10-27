package com.astarworks.astera.combat.application.service.primitives

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.vfx.SpriteGlyphs
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxQuad
import com.astarworks.astera.core.domain.effect.vfx.veil.ScreenVeilMath
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * `screen_veil` (ADR-0289): a large sprite quad `distance` blocks in front of a player's eye along the look direction,
 * re-anchored every tick through teleport interpolation, visible only to the mechanic's `viewers` (audience-scoped spawn)
 * and fading out over `ticks`. Its side is clamped so the projected area stays within `max_screen_fraction`
 * ([ScreenVeilMath], FOV 70, 16:9).
 *
 * The per-tick follow runs on the target's entity scheduler (Folia). A viewer holds at most [MAX_CONCURRENT] veils.
 */
internal class ScreenVeilHandler(private val deps: PrimitiveDeps) : PrimitiveHandler<Mechanic.ScreenVeil> {

    private val live = ConcurrentHashMap<PlayerId, AtomicInteger>()

    override fun handle(mechanic: Mechanic.ScreenVeil, ctx: MechanicContext) {
        val displays = deps.displays ?: return deps.log.debug("screen_veil dropped: no display gateway")
        val world = ctx.worldId ?: return deps.log.debug("screen_veil dropped: ctx.worldId is null")
        val ticks = deps.host.resolveInt(mechanic.ticks, ctx).coerceAtLeast(1)
        val distance = deps.host.resolveDouble(mechanic.distance, ctx).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        val requested = SPRITE_BASE_SIDE * deps.host.resolveDouble(mechanic.scale, ctx)
        val fraction = deps.host.resolveDouble(mechanic.maxScreenFraction, ctx)
        val side = ScreenVeilMath.clampSide(requested, distance, fraction)
        val rgb = mechanic.colorRgb ?: 0xFFFFFF
        val kind = VfxElementKind.Quad(colorRgb = rgb, glyph = SpriteGlyphs.text(mechanic.sprite, 0, rgb))
        val transform = VfxQuad.transform(kind, com.astarworks.astera.core.domain.geometry.math.Quaternion.IDENTITY, Vec3(side, side, 1.0))
        val text = VfxQuad.text(kind)

        for (viewer in deps.targeters.resolvePlayers(mechanic.targets, ctx).take(MAX_VIEWERS)) {
            val pose = poseOf(viewer, ctx) ?: continue
            if (pose.world != world) continue
            val counter = live.computeIfAbsent(viewer) { AtomicInteger() }
            if (counter.incrementAndGet() > MAX_CONCURRENT) {
                counter.decrementAndGet()
                deps.log.debug("screen_veil dropped for {}: {} veils already live", viewer, MAX_CONCURRENT)
                continue
            }
            val spec = DisplaySpec(
                kind = DisplayKind.Text(
                    lines = listOf(LocalizedText(mapOf(LocaleCode.EN to text, LocaleCode.JA to text))),
                    backgroundArgb = 0,
                    shadow = false,
                    seeThrough = false,
                    textOpacity = 255,
                ),
                transform = transform,
                lifetimeTicks = (ticks + 2).toLong(),
                billboard = DisplayBillboard.CENTER,
                brightness = 15,
                viewRange = 8f,
            )
            val handle = displays.spawn(world, anchor(pose, distance), spec, listOf(text), mechanic.viewers, ctx.actorId, viewer)
            if (handle == null) {
                counter.decrementAndGet()
                deps.log.warn("[cue-dropped] screen_veil: display was not spawned for {}", viewer)
                continue
            }
            follow(displays, handle, viewer, ctx, distance, ticks, counter)
        }
    }

    /** Re-anchors the veil in front of [viewer]'s eye each tick and fades it; removes it at the end. */
    private fun follow(
        displays: IDisplayEntityGateway,
        handle: IDisplayEntityGateway.DisplayHandle,
        viewer: PlayerId,
        ctx: MechanicContext,
        distance: Double,
        ticks: Int,
        counter: AtomicInteger,
    ) {
        val scope = ExecutionScope.Entity(viewer.uuid)
        var age = 0
        var task: IScheduler.TaskHandle? = null
        fun finish() {
            task?.cancel()
            displays.remove(handle)
            counter.decrementAndGet()
        }
        task = deps.scheduler.repeat(scope, ticks(1), ticks(1)) {
            age++
            val pose = poseOf(viewer, ctx)
            if (age > ticks || pose == null) return@repeat finish()
            val (yaw, pitch) = yawPitch(pose.direction)
            displays.teleport(handle, anchor(pose, distance), yaw, pitch, TELEPORT_TICKS)
            val opacity = ((1.0 - age.toDouble() / ticks) * 255.0).toInt().coerceIn(VfxQuad.MIN_TEXT_OPACITY, 255)
            displays.setTextStyle(handle, 0, opacity, null)
        }
    }

    private fun poseOf(viewer: PlayerId, ctx: MechanicContext): PlayerPose? =
        deps.poseLookup?.poseOf(viewer) ?: ctx.pose.takeIf { viewer == ctx.actorId }

    private fun anchor(pose: PlayerPose, distance: Double): Vec3 {
        val d = pose.direction
        val len = sqrt(d.x * d.x + d.y * d.y + d.z * d.z).takeIf { it > 1e-9 } ?: 1.0
        return pose.eyePosition + Vec3(d.x / len, d.y / len, d.z / len) * distance
    }

    /** Minecraft yaw / pitch (degrees) of a look direction. */
    private fun yawPitch(d: Vec3): Pair<Float, Float> {
        val yaw = Math.toDegrees(atan2(-d.x, d.z))
        val pitch = -Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))
        return yaw.toFloat() to pitch.toFloat()
    }

    internal companion object {
        /** Side in blocks of a sprite at `scale: 1` (the 8 px glyph cell, [VfxQuad] calibration). */
        const val SPRITE_BASE_SIDE = 0.2
        const val MIN_DISTANCE = 0.3
        const val MAX_DISTANCE = 1.0
        const val MAX_VIEWERS = 8
        const val MAX_CONCURRENT = 2
        const val TELEPORT_TICKS = 1
    }
}
