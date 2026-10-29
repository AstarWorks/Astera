package com.astarworks.astera.core.application.service.animation

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry
import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigPlayback
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Headless [ISkinRigAnimator] (ADR-0250): runs the same [SkinRigPlayback] timeline as the Paper
 * adapter but renders nothing, so game-sim and the command tree see identical start / stop /
 * finish behaviour. Overlaid at priority 100 by `PaperSkinRigAnimator` on a real server.
 */
public class InMemorySkinRigAnimator(
    private val registry: () -> ISkinRigRegistry?,
    private val scheduler: IScheduler,
) : ISkinRigAnimator {

    private class Run(val playback: SkinRigPlayback, var task: IScheduler.TaskHandle? = null)

    private val runs = ConcurrentHashMap<PlayerId, Run>()

    override fun play(playerId: PlayerId, animation: CustomAnimationId, speed: Double, holds: List<com.astarworks.astera.core.domain.animation.skinrig.SkinRigHold>): Result<Unit, SkinRigError> {
        val definition = registry()?.find(animation) ?: return Result.failure(SkinRigError.UnknownAnimation(animation))
        runs.remove(playerId)?.task?.cancel()
        val run = Run(SkinRigPlayback(definition, speed, holds))
        runs[playerId] = run
        run.task = scheduler.repeat(ExecutionScope.Entity(playerId.uuid), ticks(1), ticks(1)) {
            run.playback.advance()
            if (run.playback.isFinished) {
                run.task?.cancel()
                runs.remove(playerId, run)
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
}
