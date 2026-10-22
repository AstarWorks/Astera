package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPhase

/**
 * Win-condition resolver for Annihilation matches (ADR-0108, Wave 5annihilation).
 *
 * Two win conditions:
 *
 *  1. **Elimination**: only one team has a surviving Tier 3 pillar →
 *     that team wins. Can fire in any phase from
 *     [NexusPhase.Phase3Tier3] onward (Phase 2 cannot reach this since
 *     Tier 3 is still in [com.astarworks.astera.core.domain.nexus.NexusState.BUILDING]
 *     and cannot be destroyed; defensive checks still cover Phase 2
 *     against bad state).
 *  2. **Sudden-death timer expiry**: in [NexusPhase.Phase4SuddenDeath],
 *     if multiple teams remain when `currentTick - phaseEnteredAtTick
 *     >= config.suddenDeathTicks`, the team with the highest
 *     [AnnihilationScore] wins (tiebreakers in score's compareTo).
 *
 * Both conditions can produce **draw outcomes** (no winner):
 *
 *  - Simultaneous elimination: all surviving Tier 3s fall on the same
 *    tick (very rare; defensive guard).
 *  - Sudden-death tiebreaker tied at every level (score + pillarsAlive +
 *    tier3Alive).
 *
 * The tracker calls [evaluate] after every damage application and once
 * per tick. A non-`InProgress` outcome ends the match via
 * `IMatchLifecycle.endMatch`.
 */
public object WinConditionRules {

    /**
     * Outcome of a win-condition evaluation.
     */
    public sealed interface Outcome {

        /** Match continues. */
        public data object InProgress : Outcome

        /** A single team is the winner. */
        public data class Winner(val team: TeamId) : Outcome

        /** No winner — multiple teams tied at the end. */
        public data object Draw : Outcome
    }

    /**
     * Resolve the current outcome from [state] at [currentTick].
     */
    public fun evaluate(state: AnnihilationMatchState, currentTick: Long): Outcome {
        // A single-team match (solo playtest, min_players 1 with the other teams
        // vacated) has nobody to beat: there is no winner by elimination.
        if (state.allTeams.size < 2) return Outcome.InProgress
        val survivors = state.survivingTeams
        // Elimination — only one team has a Tier 3 left.
        if (survivors.size == 1) return Outcome.Winner(survivors.single())
        if (survivors.isEmpty()) return Outcome.Draw

        // Multiple survivors — only the sudden-death timer can decide.
        if (state.phase != NexusPhase.Phase4SuddenDeath) return Outcome.InProgress
        val elapsed = currentTick - state.phaseEnteredAtTick
        if (elapsed < state.config.suddenDeathTicks) return Outcome.InProgress

        // Sudden-death timer fired — resolve via score tiebreaker.
        val scores = AnnihilationScore.computeAll(survivors, state.pillars.values)
        val top = scores.first()
        // A true tie (compareTo == 0 against #2) is a draw.
        val secondOrNull = scores.getOrNull(1)
        return if (secondOrNull != null && secondOrNull.compareTo(top) == 0) {
            Outcome.Draw
        } else {
            Outcome.Winner(top.team)
        }
    }
}
