package com.astarworks.astera.core.domain.event

/** Why a [MatchEnded] fired. */
public sealed interface EndReason {
    public data object ScoreThresholdReached : EndReason
    public data object TimerExpired : EndReason
    public data object AdminEnded : EndReason
    public data object SuddenDeathResolved : EndReason
    public data class Cancelled(val why: String) : EndReason
}
