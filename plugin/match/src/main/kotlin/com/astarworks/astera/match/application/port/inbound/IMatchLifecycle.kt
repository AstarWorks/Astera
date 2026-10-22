package com.astarworks.astera.match.application.port.inbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.usecase.match.EndMatchError
import com.astarworks.astera.match.application.usecase.match.EntryGameError
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseError
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.event.EndReason

/**
 * Inbound port aggregating the four match lifecycle use cases.
 *
 * Cross-plugin callers (Stats, Economy) resolve this from [ServiceRegistry]
 * instead of wiring four separate use-case references.
 */
public interface IMatchLifecycle {

    /**
     * Creates a match for [stageId] and starts the countdown.
     *
     * @param modeKey optional [com.astarworks.astera.match.domain.mode.GameModeId.value]
     *   selecting the match's mode. `null` keeps the legacy 2-team Norma path
     *   (every existing caller). `"annihilation"` triggers the 3-team
     *   formation + Annihilation game-mode dispatch (Wave 5wireup §G16).
     */
    public fun start(
        stageId: StageId,
        modeKey: String? = null,
    ): Result<MatchId, StartMatchError>

    /** Ends [matchId] for the given [reason]. */
    public fun end(matchId: MatchId, reason: EndReason): Result<Unit, EndMatchError>

    /**
     * Ends [matchId] declaring [winningTeam] the winner (objective modes decide
     * the winner themselves; [end] only knows the score-threshold evaluator).
     * Implementations without winner support fall back to [end].
     */
    public fun endWithWinner(
        matchId: MatchId,
        reason: EndReason,
        winningTeam: TeamId?,
    ): Result<Unit, EndMatchError> = end(matchId, reason)

    /** Registers [playerId] into [matchId], optionally on [teamId]. */
    public fun entry(
        matchId: MatchId,
        playerId: PlayerId,
        teamId: TeamId?,
    ): Result<Unit, EntryGameError>

    /** Applies [trigger] to advance [matchId]'s phase. */
    public fun switchPhase(
        matchId: MatchId,
        trigger: MatchStateMachine.TransitionTrigger,
    ): Result<MatchPhase, SwitchPhaseError>
}
