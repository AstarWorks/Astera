package com.astarworks.astera.core.application.service.animation

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry
import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator
import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigPuppet
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigPlayback
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * The one [ISkinRigAnimator] (ADR-0250, ADR-0315): owns a [SkinRigPlayback] per player, advances it once per server
 * tick and pushes each sampled frame to the player's [ISkinRigPuppet]. The same class runs headless in game-sim (the
 * in-memory puppet renders nothing) and on a server (the Paper puppet draws the Display rig), so start / stop / finish
 * behaviour is identical in both.
 */
public class SkinRigClipPlayer(
    private val registry: () -> ISkinRigRegistry?,
    private val scheduler: IScheduler,
    private val puppet: () -> ISkinRigPuppet?,
) : ISkinRigAnimator {

    private class Run(val playback: SkinRigPlayback, var task: IScheduler.TaskHandle? = null)

    private val runs = ConcurrentHashMap<PlayerId, Run>()

    override fun play(playerId: PlayerId, animation: CustomAnimationId, speed: Double): Result<Unit, SkinRigError> {
        val definition = registry()?.find(animation) ?: return Result.failure(SkinRigError.UnknownAnimation(animation))
        end(playerId)
        val puppet = puppet()
        if (puppet != null) {
            when (val shown = puppet.show(playerId, definition, heldItem = true)) {
                is Result.Failure -> return Result.failure(shown.error)
                is Result.Success -> {}
            }
        }
        val run = Run(SkinRigPlayback(definition, speed))
        runs[playerId] = run
        puppet?.pose(playerId, run.playback.frame(), null)
        run.task = scheduler.repeat(ExecutionScope.Entity(playerId.uuid), ticks(1), ticks(1)) {
            run.playback.advance()
            val live = puppet()
            val ours = live == null || live.shown(playerId) == definition.id
            when {
                // the puppet went away under us (lease revoked, quit, a skill performance took the body): the run ends, nothing to hide
                !ours -> { run.task?.cancel(); runs.remove(playerId, run) }
                run.playback.isFinished -> { run.task?.cancel(); if (runs.remove(playerId, run)) live?.hide(playerId) }
                else -> live?.pose(playerId, run.playback.frame(), null)
            }
        }
        return Result.success(Unit)
    }

    override fun stop(playerId: PlayerId): Result<Unit, SkinRigError> {
        val run = runs[playerId] ?: return Result.failure(SkinRigError.NotPlaying(playerId))
        run.playback.requestStop()
        return Result.success(Unit)
    }

    override fun active(playerId: PlayerId): CustomAnimationId? = runs[playerId]?.playback?.animation?.id

    private fun end(playerId: PlayerId) {
        val old = runs.remove(playerId) ?: return
        old.task?.cancel()
        puppet()?.hide(playerId)
    }
}
