package com.astarworks.astera.combat.application.service.primitives

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec
import com.astarworks.astera.core.domain.effect.sound.SoundPlanner
import com.astarworks.astera.core.domain.effect.sound.TimedSound
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic

/**
 * `play_sound` with `pitch_arc`, `scale` and `viewers` (ADR-0290).
 *
 * Pitch: a `pitch_arc` is expanded per tick by [SoundPlanner] (overrides `pitch` and `scale`); a `scale` picks the step
 * `tickIndex % steps` (the index a `loop` mechanic hands its children), multiplied by the base pitch. All pitches are clamped
 * to the vanilla 0.5..2.0 range.
 *
 * Audience: `all` goes through the shared sound scheduler (everyone in range hears it). `caster` / `target` / `others` play
 * per viewer through [com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway], each step on that
 * viewer's entity scheduler. `others` = players resolved by the mechanic's targeter, minus the caster.
 */
internal class SoundMechanics(private val deps: PrimitiveDeps) {

    fun play(mechanic: Mechanic.PlaySound, ctx: MechanicContext) {
        val locations = deps.targeters.resolveLocations(mechanic.targets, ctx)
        if (locations.isEmpty()) return
        val world = ctx.worldId
        if (world == null) {
            deps.log.debug("PlaySound {} dropped: ctx.worldId is null", mechanic.soundId)
            return
        }
        val definition = deps.soundRegistry?.find(mechanic.soundId)
        val volume = (deps.host.resolveDouble(mechanic.volume, ctx).takeIf { it > 0.0 } ?: (definition?.defaultVolume ?: 1.0))
            .coerceIn(0.0, SoundSpec.PRACTICAL_MAX_VOLUME)
        val basePitch = deps.host.resolveDouble(mechanic.pitch, ctx).takeIf { it > 0.0 } ?: (definition?.defaultPitch ?: 1.0)
        val key = definition?.resourceKey ?: mechanic.soundId.value

        val viewers = viewersOf(mechanic, ctx)
        if (mechanic.viewers != Audience.ALL && viewers.isEmpty()) return

        for (loc in locations) {
            val steps = stepsFor(mechanic, ctx, key, volume, basePitch, loc)
            if (mechanic.viewers == Audience.ALL) {
                emitToAll(steps, loc, ctx, world)
            } else {
                emitToViewers(steps, loc, viewers, world)
            }
        }
    }

    private fun stepsFor(
        mechanic: Mechanic.PlaySound,
        ctx: MechanicContext,
        key: String,
        volume: Double,
        basePitch: Double,
        loc: Vec3,
    ): List<TimedSound> {
        mechanic.pitchArc?.let { return SoundPlanner.arcSequence(key, volume, it, loc).steps }
        val pitch = mechanic.scale
            ?.let { SoundPlanner.clampPitch(basePitch * SoundPlanner.scalePitch(it, ctx.tickIndex)) }
            ?: SoundPlanner.clampPitch(basePitch)
        return listOf(TimedSound(SoundSpec(key, volume, pitch), loc, 0L))
    }

    private fun emitToAll(steps: List<TimedSound>, loc: Vec3, ctx: MechanicContext, world: com.astarworks.astera.core.domain.world.WorldId) {
        val ss = deps.soundScheduler
        if (ss != null) {
            if (steps.size == 1) {
                ss.schedule(ScheduledSoundSpec.EmitOnce(steps[0].sound, loc, deps.scheduler.currentTick(), world))
            } else {
                ss.schedule(ScheduledSoundSpec.Sequence(steps, world))
            }
            return
        }
        for (step in steps) {
            if (step.atTick == 0L) {
                deps.worldGateway.playSound(world, loc, step.sound)
            } else {
                deps.scheduler.runLaterTicks(ExecutionScope.Entity(ctx.actorId.uuid), step.atTick) {
                    deps.worldGateway.playSound(world, loc, step.sound)
                }
            }
        }
    }

    private fun emitToViewers(
        steps: List<TimedSound>,
        loc: Vec3,
        viewers: List<PlayerId>,
        world: com.astarworks.astera.core.domain.world.WorldId,
    ) {
        val gateway = deps.playerSound()
        if (gateway == null) {
            deps.log.warn("play_sound viewers != all dropped: no IPlayerSoundGateway registered")
            return
        }
        for (viewer in viewers) {
            for (step in steps) {
                if (step.atTick == 0L) {
                    gateway.playForViewer(viewer, step.sound, loc, world)
                } else {
                    deps.scheduler.runLaterTicks(ExecutionScope.Entity(viewer.uuid), step.atTick) {
                        gateway.playForViewer(viewer, step.sound, loc, world)
                    }
                }
            }
        }
    }

    private fun viewersOf(mechanic: Mechanic.PlaySound, ctx: MechanicContext): List<PlayerId> = when (mechanic.viewers) {
        Audience.ALL -> emptyList()
        Audience.CASTER -> listOf(ctx.actorId)
        Audience.TARGET -> listOfNotNull(ctx.targetId)
        Audience.OTHERS -> deps.targeters.resolvePlayers(mechanic.targets, ctx).filter { it != ctx.actorId }
    }
}
