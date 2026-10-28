package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.sound.PitchArc
import com.astarworks.astera.core.domain.effect.sound.SoundPlanner
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
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
    MeshExecutor(rt), PlateExecutor(rt), LineExecutor(rt), TrailExecutor(rt), TrackExecutor(rt), RemoveExecutor(rt),
    EmitExecutor(rt), SoundExecutor(rt),
    VictimAnimExecutor(rt), CasterAnimExecutor(rt),
    TitleExecutor(rt), ActionBarExecutor(rt), CameraShakeExecutor(rt),
)

/** Hold time of a pose-only caster clip (crouch, hide-main-hand) when the op gives no `ticks:`. */
private const val DEFAULT_POSE_TICKS = 20

private fun OpContext.passes(op: SkillOp): Boolean = op.whenExpr?.let { values.bool(it) } ?: true

private fun OpContext.text(v: Value<String>?, default: String): String = v?.let { values.text(it) } ?: default

private fun OpContext.num(v: Value<Double>?, default: Double): Double = v?.let { values.num(it) } ?: default

private fun euler(v: Vec3): Quaternion = Quaternion.fromEulerDegrees(v.x, v.y, v.z)

internal class MeshExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Mesh> {
    override val opClass: KClass<VisualOp.Mesh> = VisualOp.Mesh::class
    override fun execute(op: VisualOp.Mesh, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        val frame = rt.frameWorld(s, op.frame) ?: return
        val pivot = op.pivot?.let { ctx.values.vec3(it) } ?: Vec3(0.0, 0.0, 0.0)
        val tilt = op.tilt?.let { euler(ctx.values.vec3(it)) } ?: Quaternion.IDENTITY
        val rest = op.rest?.let { euler(ctx.values.vec3(it)) } ?: Quaternion.IDENTITY
        val base = rt.rigBase(frame, pivot, tilt)
        val pose = Frame(base.origin, (base.rotation * rest).normalized())
        val scale = ctx.num(op.scale, 1.0)
        val h = ctx.ports.visuals.spawnMesh(rt.info.owner(ctx.cast), op.item, op.part, scale, pose)
        rt.register(s, PresentationRuntime.Live.Mesh(op.id, h, op.frame, scale, pivot, tilt, rest))
    }
}

internal class PlateExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Plate> {
    override val opClass: KClass<VisualOp.Plate> = VisualOp.Plate::class
    override fun execute(op: VisualOp.Plate, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        val pose = rt.platePose(s, op.parent, op.face) ?: return
        val reference = ctx.tree.pointWorld(FrameId(op.parent), "tip").getOrNull()
            ?.let { it.distance(pose.origin) }?.takeIf { it > 1e-6 } ?: 1.0
        val size = ctx.values.num(op.sizeFrac) * reference
        val h = ctx.ports.visuals.spawnPlate(
            rt.info.owner(ctx.cast), op.sprite, op.face, size, ctx.values.num(op.alpha), pose,
        )
        val tint = op.color?.let { ctx.values.text(it) }
        val frames = ctx.ports.visuals.spriteFrames(op.sprite)
        if (tint != null) ctx.ports.visuals.setPlateFrame(h, 0, tint)
        rt.register(s, PresentationRuntime.Live.Plate(op.id, h, op.parent, op.face, size, op.sprite, frames, op.frameTicks, op.loop, tint, s.tick))
    }
}

internal class LineExecutor(private val rt: PresentationRuntime) : OpExecutor<VisualOp.Line> {
    override val opClass: KClass<VisualOp.Line> = VisualOp.Line::class
    override fun execute(op: VisualOp.Line, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val s = rt.state(ctx)
        val a = rt.pointWorld(s, op.from) ?: return
        val b = rt.pointWorld(s, op.to) ?: return
        val h = ctx.ports.visuals.spawnLine(
            rt.info.owner(ctx.cast), a, b, ctx.values.num(op.thickness), ctx.values.text(op.color), op.face,
        )
        rt.register(s, PresentationRuntime.Live.Line(op.id, h, op.from, op.to))
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
        rt.register(s, PresentationRuntime.Live.Trail(op.id, h, op.of, op.root, op.ticks, op.untilEvent))
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
        val at = rt.pointWorld(s, op.at) ?: return
        val inherit = op.inheritVelocity?.let { ctx.values.num(it) }
        val count = ctx.values.num(op.count).roundToInt().coerceAtLeast(0)
        val dir = op.dir?.let { ctx.values.vec3(it) }
        val spreadDeg = ctx.num(op.spreadDeg, 0.0)
        val speed = ctx.num(op.speed, 0.3)
        val inheritVelocity = inherit?.let { rt.velocityOf(s, op.at.frame) * it }
        val colorHex = op.color?.let { ctx.values.text(it) }
        val seed = ctx.cast.value * 31L + (op.seed ?: op.id).hashCode().toLong()
        val vanilla = op.extra != null || op.offset != null
        val offset = op.offset?.let { ctx.values.vec3(it) }
        val extra = ctx.num(op.extra, 0.0)
        val spacing = op.spacing?.let { ctx.values.num(it) } ?: 0.0
        val sweep = if (spacing > 0.0) rt.velocityOf(s, op.at.frame) else Vec3(0.0, 0.0, 0.0)
        val distance = sweep.length()
        // One play per `spacing` blocks along the segment covered since the last tick, ending at `at`.
        val steps = if (distance > spacing && spacing > 0.0) kotlin.math.ceil(distance / spacing).toInt() else 1
        for (i in 1..steps) {
            val where = if (steps == 1) at else at - sweep * (1.0 - i.toDouble() / steps)
            ctx.ports.particles.emit(
                ParticleBurst(
                    particle = op.particle, at = where, count = count, dir = dir, spreadDeg = spreadDeg, speed = speed,
                    inheritVelocity = inheritVelocity, colorHex = colorHex, seed = seed + (i - 1),
                    vanilla = vanilla, offset = offset, extra = extra,
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
        val play = {
            val at = rt.pointWorld(s, op.at)
            if (at != null) {
                val from = ctx.num(op.pitch, 1.0)
                val over = op.overTicks
                val pitch = if (op.pitchTo != null && over != null && over > 0) {
                    // v1 PitchArc exactly: eased glide, clamped to the vanilla 0.5..2.0 range.
                    val ease = op.pitchEase?.let { Easing.fromKey(it) } ?: Easing.LINEAR
                    SoundPlanner.pitchAt(PitchArc(from, ctx.num(op.pitchTo, from), over, ease), s.tick - startTick)
                } else from
                ctx.ports.sounds.play(op.sound, at, ctx.num(op.volume, 1.0).coerceAtLeast(0.0), pitch)
            }
        }
        play()
        val every = op.everyTicks
        if (every != null && every > 0) {
            val arcOver = op.overTicks
            val arcEnd = if (op.pitchTo != null && arcOver != null && arcOver > 0) startTick + arcOver else null
            s.sounds[op.id] = PresentationRuntime.SoundTask(op.id, every, until, minOf(s.tick + every, arcEnd ?: Int.MAX_VALUE), play, arcEnd)
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

internal class CameraShakeExecutor(private val rt: PresentationRuntime) : OpExecutor<ScreenOp.CameraShake> {
    override val opClass: KClass<ScreenOp.CameraShake> = ScreenOp.CameraShake::class
    override fun execute(op: ScreenOp.CameraShake, ctx: OpContext) {
        if (!ctx.passes(op)) return
        val intensity = ctx.values.num(op.intensity)
        for (v in rt.info.targets(ctx.cast, op.target)) rt.screen.cameraShake(v, intensity, op.ticks)
    }
}
