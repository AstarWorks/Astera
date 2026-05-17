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

    /** Creates a match for [stageId] and starts the countdown. */
    public fun start(stageId: StageId): Result<MatchId, StartMatchError>

    /** Ends [matchId] for the given [reason]. */
    public fun end(matchId: MatchId, reason: EndReason): Result<Unit, EndMatchError>

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
