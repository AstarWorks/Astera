package com.astarworks.astera.core.application.port.inbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialSequencerError
import com.astarworks.astera.core.domain.tutorial.TutorialStep

/** Inbound tutorial API. Start resumes paused cursors or replays completed scripts.
 * Gameplay calls recordAction after success; advance remains an internal sequencer operation.
 * Cancel implements the player-facing skip command by preserving a paused cursor.
 */
public interface ITutorialSequencerService {

    public suspend fun startSequence(
        playerId: PlayerId,
        scriptId: TutorialScriptId,
    ): Result<Unit, TutorialSequencerError>

    public suspend fun currentStep(playerId: PlayerId): TutorialStep?

    public suspend fun advance(playerId: PlayerId): Result<TutorialStep?, TutorialSequencerError>

    /** Only successful gameplay operations may supply action tokens. */
    public suspend fun recordAction(playerId: PlayerId, actionId: String): Result<Boolean, TutorialSequencerError> {
        val step = currentStep(playerId) as? TutorialStep.WaitForAction
        if (step?.actionId != actionId) return Result.success(false)
        return when (val result = advance(playerId)) {
            is Result.Success -> Result.success(true)
            is Result.Failure -> Result.failure(result.error)
        }
    }

    public suspend fun cancel(playerId: PlayerId): Result<Unit, TutorialSequencerError>
}
