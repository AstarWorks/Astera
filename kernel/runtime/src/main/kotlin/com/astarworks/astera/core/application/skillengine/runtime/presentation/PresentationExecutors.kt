package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.sound.PitchArc
import com.astarworks.astera.core.domain.effect.sound.SoundPlanner
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.BodyOp
import com.astarworks.astera.core.domain.skillengine.op.FxOp
import com.astarworks.astera.core.domain.skillengine.op.ScreenOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/** All presentation executors, bound to one [PresentationRuntime]. The manager registers them. */
public fun presentationExecutors(rt: PresentationRuntime): List<OpExecutor<*>> = listOf(
    MeshExecutor(rt), PlateExecutor(rt), BlockExecutor(rt), QuadExecutor(rt), LineExecutor(rt), TrailExecutor(rt), TrackExecutor(rt), RemoveExecutor(rt),
    EmitExecutor(rt), SoundExecutor(rt),
    VictimAnimExecutor(rt), CasterAnimExecutor(rt),
    TitleExecutor(rt), ActionBarExecutor(rt), CameraShakeExecutor(rt), FlashExecutor(rt), StopSoundExecutor(rt),
)

/** Hold time of a pose-only caster clip (crouch, hide-main-hand) when the op gives no `ticks:`. */
private const val DEFAULT_POSE_TICKS = 20

private fun OpContext.passes(op: SkillOp): Boolean = op.whenExpr?.let { values.bool(it) } ?: true

private fun OpContext.text(v: Value<String>?, default: String): String = v?.let { values.text(it) } ?: default

private fun OpContext.num(v: Value<Double>?, default: Double): Double = v?.let { values.num(it) } ?: default

/** A rotation vector (axis direction, length = degrees, right-hand): the only authored form of an orientation. */
private fun turn(v: Vec3): Quaternion = Quaternion.fromRotationVector(v)

internal class MeshExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Mesh> {
    override val opClass: KClass<VisualOp.Mesh> = VisualOp.Mesh::class
    override fun execute(op: VisualOp.Mesh, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        val pivot = op.pivot?.let { ctx.values.vec3(it) } ?: Vec3(0.0, 0.0, 0.0)
        val tilt = op.tilt?.let { turn(ctx.values.vec3(it)) } ?: Quaternion.IDENTITY
        val rest = op.rest?.let { turn(ctx.values.vec3(it)) } ?: Quaternion.IDENTITY
        val scale = ctx.num(op.scale, 1.0)
        val live = PresentationRuntime.Live.Mesh(op.id, op.frame, scale, pivot, tilt, rest)
        // The node carries the model's points (scaled to blocks), so `<mesh id>.tip` follows the swing.
        val pts = rt.points.points(op.item, op.part).map { FramePoint(live.node, it.name, it.offset * scale, it.axis) }
        val pose = rt.spawnNode(s, live, op.frame, pts) ?: return ctx.host.fail(op.id, "mesh: parent frame '${op.frame}' is not in the frame tree")
        live.handle = ctx.ports.visuals.spawnMesh(
            rt.info.owner(ctx.cast), op.item, op.part, pose, op.brightness,
            MeshLook(op.itemModel, op.displayMode ?: DisplayItemMode.FIXED, op.rigShading),
        )
        rt.register(s, live)
    }
}

internal class PlateExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Plate> {
    override val opClass: KClass<VisualOp.Plate> = VisualOp.Plate::class
    override fun execute(op: VisualOp.Plate, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        rt.repeated(s, ctx, op.id, op.parent, op.repeat) { id, parent ->
            val par = parent ?: op.parent
            val tint = op.color?.let { ctx.values.text(it) }
            val frames = ctx.ports.visuals.sprite(op.sprite).frames
            val live = PresentationRuntime.Live.Plate(
                id, par, op.face, ctx.values.num(op.size), rt.billboardOf(s, op.face, par),
                op.sprite, frames, op.frameTicks, op.loop, tint, s.tick,
                op.at?.let { ctx.values.vec3(it) } ?: Vec3.ZERO, op.turn?.let { turn(ctx.values.vec3(it)) } ?: Quaternion.IDENTITY,
            )
            val pose = rt.spawnNode(s, live, par) ?: run { ctx.host.fail(op.id, "plate: parent frame '$par' is not in the frame tree"); return@repeated null }
            live.handle = ctx.ports.visuals.spawnPlate(rt.info.owner(ctx.cast), op.sprite, ctx.values.num(op.alpha), pose, op.brightness)
            if (tint != null) ctx.ports.visuals.setPlateFrame(live.handle, 0, tint)
            rt.register(s, live)
            live
        }
    }
}

internal class BlockExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Block> {
    override val opClass: KClass<VisualOp.Block> = VisualOp.Block::class
    override fun execute(op: VisualOp.Block, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        rt.repeated(s, ctx, op.id, op.parent, op.repeat) { id, parent ->
            val par = parent ?: op.parent
            val live = PresentationRuntime.Live.Block(
                id, par, ctx.values.vec3(op.size),
                op.at?.let { ctx.values.vec3(it) } ?: Vec3.ZERO, op.turn?.let { turn(ctx.values.vec3(it)) } ?: Quaternion.IDENTITY,
                op.pivot?.let { ctx.values.vec3(it) },
                op.tilt?.let { turn(ctx.values.vec3(it)) } ?: Quaternion.IDENTITY,
                op.rest?.let { turn(ctx.values.vec3(it)) } ?: Quaternion.IDENTITY,
            )
            val pose = rt.spawnNode(s, live, par) ?: run { ctx.host.fail(op.id, "block: parent frame '$par' is not in the frame tree"); return@repeated null }
            live.handle = ctx.ports.visuals.spawnBlock(rt.info.owner(ctx.cast), op.block, pose, op.brightness, op.rigShading)
            rt.register(s, live)
            live
        }
    }
}

internal class QuadExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Quad> {
    override val opClass: KClass<VisualOp.Quad> = VisualOp.Quad::class
    override fun execute(op: VisualOp.Quad, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        rt.repeated(s, ctx, op.id, op.parent, op.repeat) { id, parent ->
            val par = parent ?: op.parent
            val live = PresentationRuntime.Live.Quad(
                id, par, op.face, ctx.values.vec3(op.size), rt.billboardOf(s, op.face, par),
                op.at?.let { ctx.values.vec3(it) } ?: Vec3.ZERO, op.turn?.let { turn(ctx.values.vec3(it)) } ?: Quaternion.IDENTITY,
            )
            val pose = rt.spawnNode(s, live, par) ?: run { ctx.host.fail(op.id, "quad: parent frame '$par' is not in the frame tree"); return@repeated null }
            live.handle = ctx.ports.visuals.spawnQuad(
                rt.info.owner(ctx.cast), ctx.values.text(op.color), ctx.values.num(op.alpha), op.text, op.shadow, op.seeThrough, pose, op.brightness,
            )
            rt.register(s, live)
            live
        }
    }
}

internal class LineExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Line> {
    override val opClass: KClass<VisualOp.Line> = VisualOp.Line::class
    override fun execute(op: VisualOp.Line, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        val live = PresentationRuntime.Live.Line(op.id, op.from, op.to, ctx.values.num(op.thickness), op.face)
        val pose = rt.linePose(s, live) ?: return ctx.host.fail(op.id, "line: ${op.from} -> ${op.to} does not resolve to two distinct points")
        live.handle = ctx.ports.visuals.spawnLine(rt.info.owner(ctx.cast), ctx.values.text(op.color), pose)
        rt.register(s, live)
    }
}

internal class TrailExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Trail> {
    override val opClass: KClass<VisualOp.Trail> = VisualOp.Trail::class
    override fun execute(op: VisualOp.Trail, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        val h = ctx.ports.visuals.spawnTrail(
            rt.info.owner(ctx.cast), op.ticks, ctx.values.num(op.widthFrac), ctx.values.text(op.color), ctx.values.num(op.alpha),
        )
        if (op.fade != null || op.style != "soft") ctx.ports.visuals.setTrailLook(h, op.fade?.let { ctx.values.num(it) } ?: 1.0, op.style)
        if (op.bands.isNotEmpty() || op.edge != null || op.darkRim != null || op.stepDeg != 30.0 || op.doubleSided) {
            ctx.ports.visuals.setTrailShape(h, op.bands, op.edge, op.darkRim, op.stepDeg, op.doubleSided)
        }
        rt.register(s, PresentationRuntime.Live.Trail(op.id, op.of, op.root, op.ticks, op.untilEvent, op.lagTicks).also { it.handle = h })
    }
}

internal class TrackExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Track> {
    override val opClass: KClass<VisualOp.Track> = VisualOp.Track::class
    override fun execute(op: VisualOp.Track, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        s.tracks[op.id] = PresentationRuntime.TrackState(op, s.tick)
    }
}

internal class RemoveExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Remove> {
    override val opClass: KClass<VisualOp.Remove> = VisualOp.Remove::class
    override fun execute(op: VisualOp.Remove, ctx: OpContext) {
        if (!ctx.passes(op)) return
        rt.removeIds(rt.state(ctx), op.ids)
    }
}

internal class EmitExecutor(private val rt: PresentationRuntime) : OpExecutor<FxOp.Emit> {
    override val opClass: KClass<FxOp.Emit> = FxOp.Emit::class
    override fun execute(op: FxOp.Emit, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        rt.repeated(s, ctx, op.id, null, op.repeat) { _, _ -> emitOnce(op, ctx, s); null }
    }

    private fun emitOnce(op: FxOp.Emit, ctx: OpContext, s: PresentationRuntime.CastState) {
        val base = rt.pointWorld(s, op.at) ?: return ctx.host.fail(op.id, "emit: point '${op.at}' does not resolve")
        val at = op.shift?.let { sh -> rt.frameWorld(s, op.at.frame)?.let { base + it.directionToWorld(ctx.values.vec3(sh)) } } ?: base
        val inherit = op.inheritVelocity?.let { ctx.values.num(it) }
        val count = op.count?.let { ctx.values.num(it).roundToInt().coerceAtLeast(0) }
        val dir = op.dir?.let { ctx.values.vec3(it) }
        val spreadDeg = ctx.num(op.spreadDeg, 0.0)
        val speed = ctx.num(op.speed, 0.0)
        val inheritVelocity = inherit?.let { rt.velocityOf(s, op.at.frame) * it }
        val colorHex = op.data?.let { ctx.values.text(it) }
        val dataSize = op.dataSize?.let { ctx.values.num(it) }
        val seed = ctx.cast.value * 31L + (op.seed ?: op.id).hashCode().toLong()
        val offset = op.offset?.let { ctx.values.vec3(it) } ?: Vec3(0.0, 0.0, 0.0)
        val look = ctx.host.lookDirection()
        val spacing = op.spacing?.let { ctx.values.num(it) } ?: 0.0
        val sweep = if (spacing > 0.0) rt.velocityOf(s, op.at.frame) else Vec3(0.0, 0.0, 0.0)
        val distance = sweep.length()
        // One play per `spacing` blocks along the segment covered since the last tick, ending at `at`.
        val steps = if (distance > spacing && spacing > 0.0) kotlin.math.ceil(distance / spacing).toInt() else 1
        for (i in 1..steps) {
            val where = if (steps == 1) at else at - sweep * (1.0 - i.toDouble() / steps)
            ctx.ports.particles.emit(
                ParticleBurst(
                    particle = op.particle, at = where, count = count, offset = offset, speed = speed,
                    colorHex = colorHex, dataSize = dataSize, dir = dir, spreadDeg = spreadDeg,
                    inheritVelocity = inheritVelocity, look = look, seed = seed + (i - 1),
                ),
            )
        }
    }
}

internal class SoundExecutor(private val rt: PresentationRuntime) : OpExecutor<FxOp.Sound> {
    override val opClass: KClass<FxOp.Sound> = FxOp.Sound::class
    override fun execute(op: FxOp.Sound, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        val until = op.untilEvent
        if (until != null && rt.info.hasRaised(ctx.cast, until)) return
        val startTick = s.tick
        val play = { elapsed: Int ->
            val at = rt.pointWorld(s, op.at)
            if (at != null) {
                val from = ctx.num(op.pitch, 1.0)
                val over = op.overTicks
                val pitch = if (op.pitchTo != null && over != null && over > 0) {
                    // v1 PitchArc exactly: eased glide, clamped to the vanilla 0.5..2.0 range.
                    val ease = op.pitchEase?.let { Easing.fromKey(it) } ?: Easing.LINEAR
                    SoundPlanner.pitchAt(PitchArc(from, ctx.num(op.pitchTo, from), over, ease), elapsed)
                } else from
                // Vanilla pitch range, as v1 played it: 0.45 sounds at 0.5.
                ctx.ports.sounds.play(op.sound, at, ctx.num(op.volume, 1.0).coerceAtLeast(0.0), pitch.coerceIn(0.5, 2.0))
            }
        }
        play(0)
        val every = op.everyTicks
        if (every != null && every > 0) {
            val arcOver = op.overTicks
            val arcEnd = if (op.pitchTo != null && arcOver != null && arcOver > 0) startTick + arcOver else null
            s.sounds[op.id] = PresentationRuntime.SoundTask(op.id, every, until, startTick, minOf(startTick + every, arcEnd ?: Int.MAX_VALUE), play, arcEnd)
        }
    }
}

internal class VictimAnimExecutor(private val rt: PresentationRuntime) : OpExecutor<BodyOp.VictimAnim> {
    override val opClass: KClass<BodyOp.VictimAnim> = BodyOp.VictimAnim::class
    override fun execute(op: BodyOp.VictimAnim, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val away = ctx.tree.pointWorld(FrameId("contact"), "point").getOrNull()
            ?: ctx.tree.worldOf(FrameId("caster")).getOrNull()?.origin
            ?: Vec3(0.0, 0.0, 0.0)
        val strength = ctx.num(op.strength, 1.0)
        for (victim in rt.info.targets(ctx.cast, op.target)) {
            ctx.ports.victimAnim.play(victim, op.anim, strength, away)
        }
    }
}

internal class CasterAnimExecutor(private val rt: PresentationRuntime) : OpExecutor<BodyOp.CasterAnim> {
    override val opClass: KClass<BodyOp.CasterAnim> = BodyOp.CasterAnim::class
    override fun execute(op: BodyOp.CasterAnim, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val until = op.untilEvent
        if (until != null && rt.info.hasRaised(ctx.cast, until)) return
        val owner = rt.info.owner(ctx.cast)
        rt.casterAnim.play(owner, op.anim, ctx.num(op.speed, 1.0), op.ticks ?: DEFAULT_POSE_TICKS)
        rt.state(ctx).anims += PresentationRuntime.AnimRun(owner, op.anim, until)
    }
}

internal class TitleExecutor(private val rt: PresentationRuntime) : OpExecutor<ScreenOp.Title> {
    override val opClass: KClass<ScreenOp.Title> = ScreenOp.Title::class
    override fun execute(op: ScreenOp.Title, ctx: OpContext) {
        if (!ctx.passes(op)) return
        for (v in rt.info.targets(ctx.cast, op.target)) rt.screen.title(v, op.text, op.subtitle, op.ticks)
    }
}

internal class ActionBarExecutor(private val rt: PresentationRuntime) : OpExecutor<ScreenOp.ActionBar> {
    override val opClass: KClass<ScreenOp.ActionBar> = ScreenOp.ActionBar::class
    override fun execute(op: ScreenOp.ActionBar, ctx: OpContext) {
        if (!ctx.passes(op)) return
        for (v in rt.info.targets(ctx.cast, op.target)) rt.screen.actionBar(v, op.text)
    }
}

internal class FlashExecutor(private val rt: PresentationRuntime) : OpExecutor<ScreenOp.Flash> {
    override val opClass: KClass<ScreenOp.Flash> = ScreenOp.Flash::class
    override fun execute(op: ScreenOp.Flash, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val overlay = com.astarworks.astera.core.domain.effect.ScreenOverlay.fromYaml(op.overlay)
            ?: return ctx.host.fail(op.id, "screen_flash: unknown overlay '${op.overlay}'")
        val rgb = op.colorRgb ?: overlay.defaultColor
        for (v in rt.info.targets(ctx.cast, op.target)) rt.screen.flash(v, overlay, rgb, op.fadeIn, op.stay, op.fadeOut)
    }
}

internal class StopSoundExecutor(private val rt: PresentationRuntime) : OpExecutor<ScreenOp.StopSound> {
    override val opClass: KClass<ScreenOp.StopSound> = ScreenOp.StopSound::class
    override fun execute(op: ScreenOp.StopSound, ctx: OpContext) {
        if (!ctx.passes(op)) return
        for (v in rt.info.targets(ctx.cast, op.target)) rt.screen.stopSound(v, op.sound)
    }
}

internal class CameraShakeExecutor(private val rt: PresentationRuntime) : OpExecutor<ScreenOp.CameraShake> {
    override val opClass: KClass<ScreenOp.CameraShake> = ScreenOp.CameraShake::class
    override fun execute(op: ScreenOp.CameraShake, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val intensity = ctx.values.num(op.intensity)
        for (v in rt.info.targets(ctx.cast, op.target)) rt.screen.cameraShake(v, intensity, op.ticks)
    }
}
