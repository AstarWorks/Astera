package com.astarworks.astera.core.domain.event

/** Why a [MatchEnded] fired. */
public sealed interface EndReason {
    public data object ScoreThresholdReached : EndReason
    public data object TimerExpired : EndReason
    public data object AdminEnded : EndReason
    public data object SuddenDeathResolved : EndReason

    /**
     * Wave 5c: a game mode's primary objective was completed by a team
     * (CarryTNT / CarryMiniTNT delivery threshold met, BreakCore destroyed,
     * etc.). Listeners publishing [com.astarworks.astera.match.domain.event.MatchEnded]
     * with this reason should populate `winningTeam` with the objective-
     * completing team.
     */
    public data object ObjectiveCompleted : EndReason
    public data class Cancelled(val why: String) : EndReason
}
