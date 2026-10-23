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
     * etc.). Listeners publishing [com.astarworks.astera.core.domain.event.MatchEnded]
     * with this reason should populate `winningTeam` with the objective-
     * completing team.
     */
    public data object ObjectiveCompleted : EndReason

    /**
     * Wave L 5hardening-complete (ADR-0129): the match's world unloaded out
     * from under the match (operator `/unload`, server restart mid-match,
     * world-corruption-driven Bukkit unload, etc.). The match was force-
     * terminated because its world is no longer addressable; no winner,
     * no objective evaluation. Distinct from
     * [Cancelled] (which is admin-initiated and carries a free-form
     * reason) — `WorldUnloaded` is a structured, well-known cause that
     * downstream listeners can branch on (e.g. skip player-summary GUIs
     * because the world is gone).
     */
    public data object WorldUnloaded : EndReason
    public data class Cancelled(val why: String) : EndReason

    /**
     * WP10: the match was decided by a mode's own rule (Annihilation: last
     * team with a Nexus standing, or the sudden-death tiebreak). [winner] is
     * carried into `MatchEnded.winningTeam` as is; `null` is a draw. A
     * score-threshold evaluation cannot know this result.
     */
    public data class TeamVictory(val winner: com.astarworks.astera.core.domain.match.TeamId?) : EndReason

    /**
     * Norma: [winner] was the first team to reach the kill goal ([goal] kills). Carries the winner into
     * `MatchEnded.winningTeam` like [TeamVictory], but names the real reason on the result screen.
     */
    public data class NormaReached(
        val winner: com.astarworks.astera.core.domain.match.TeamId,
        val goal: Int,
    ) : EndReason

    /** Norma: the time limit ran out; [leader] was strictly ahead on kills, `null` is a draw. */
    public data class NormaTimeLimit(val leader: com.astarworks.astera.core.domain.match.TeamId?) : EndReason
}
