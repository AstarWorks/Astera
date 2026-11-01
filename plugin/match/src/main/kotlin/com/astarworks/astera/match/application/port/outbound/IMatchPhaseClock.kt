package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.NexusPhase

/**
 * Where a phase-driven match (Annihilation, ADR-0319) stands on its clock: the current [NexusPhase] and how long it still
 * runs. Registered in the `ServiceRegistry` by the match plugin so the Mid ore gate, the HUD and the star economy read one
 * source instead of re-deriving it. A match without a phase machine has no snapshot (null), and nothing is gated by phase.
 */
public interface IMatchPhaseClock {

    /** The current phase of [matchId] and the ticks until it ends, or null when the match has no phase machine. */
    public fun current(matchId: MatchId): MatchPhaseSnapshot?

    /**
     * Ticks until [target] begins; 0 when it already has; null when the match has no phase machine. The match's own
     * timeout counts as the end of [NexusPhase.Phase5SuddenDeath], never as a phase.
     */
    public fun ticksUntil(matchId: MatchId, target: NexusPhase): Long?
}

/**
 * [phase] is running; [remainingTicks] until it ends (the next phase begins, or - in the last phase - the match times out
 * into the HP tiebreak). [phaseTicks] is the phase's full length, so `remainingTicks / phaseTicks` is the bar progress.
 */
public data class MatchPhaseSnapshot(val phase: NexusPhase, val remainingTicks: Long, val phaseTicks: Long)
