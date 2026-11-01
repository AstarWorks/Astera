package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPhase

/**
 * Win-condition resolver for Annihilation matches (ADR-0108, Wave 5annihilation).
 *
 * Two win conditions:
 *
 *  1. **Elimination**: only one team has a surviving Tier 3 pillar →
 *     that team wins. Can fire in any phase from
 *     [NexusPhase.Phase3Center] onward (Phase 2 cannot reach this since
 *     Tier 3 is still in [com.astarworks.astera.core.domain.nexus.NexusState.BUILDING]
 *     and cannot be destroyed; defensive checks still cover Phase 2
 *     against bad state).
 *  2. **Sudden-death timer expiry**: in [NexusPhase.Phase5SuddenDeath],
 *     if multiple teams remain when `currentTick - phaseEnteredAtTick
 *     >= config.suddenDeathTicks`, the team with the highest
 *     summed remaining pillar HP wins (ADR-0319); an exact tie at the top goes to the chain damage dealt to enemy pillars, then kills, then a draw.
 *
 * Both conditions can produce **draw outcomes** (no winner):
 *
 *  - Simultaneous elimination: all surviving Tier 3s fall on the same
 *    tick (very rare; defensive guard).
 *  - Sudden-death pillar HP tied exactly between the top teams.
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
        public data class Winner(
            val team: TeamId,
            /** Null = decided by elimination; otherwise the clock criterion that broke the field. */
            val decidedBy: EndReason.PillarHpDecision.Criterion? = null,
        ) : Outcome

        /** No winner — multiple teams tied at the end. */
        public data object Draw : Outcome
    }

    /**
     * Resolve the current outcome from [state] at [currentTick].
     */
    public fun evaluate(
        state: AnnihilationMatchState,
        currentTick: Long,
        tiebreak: Tiebreak = Tiebreak.NONE,
    ): Outcome {
        // A single-team match (solo playtest, min_players 1 with the other teams
        // vacated) has nobody to beat: there is no winner by elimination.
        if (state.allTeams.size < 2) return Outcome.InProgress
        val survivors = state.survivingTeams
        // Elimination — only one team has a Tier 3 left.
        if (survivors.size == 1) return Outcome.Winner(survivors.single())
        if (survivors.isEmpty()) return Outcome.Draw

        // Multiple survivors — only the sudden-death timer can decide.
        if (state.phase != NexusPhase.Phase5SuddenDeath) return Outcome.InProgress
        val elapsed = currentTick - state.phaseEnteredAtTick
        if (elapsed < state.config.suddenDeathTicks) return Outcome.InProgress

        // Clock decision: most summed pillar HP. Only an exact tie at the top goes on to the tiebreak chain among the tied
        // teams: damage dealt to enemy pillars, then kills; still level = draw. Team order never decides.
        val scores = AnnihilationScore.computeAll(survivors, state.pillars.values)
        val topHp = scores.first().score
        var tied = scores.filter { it.score == topHp }.map { it.team }
        if (tied.size == 1) return Outcome.Winner(tied.single(), EndReason.PillarHpDecision.Criterion.PILLAR_HP)
        for ((criterion, values) in listOf(
            EndReason.PillarHpDecision.Criterion.DAMAGE_DEALT to tiebreak.damageDealt,
            EndReason.PillarHpDecision.Criterion.KILLS to tiebreak.kills,
        )) {
            val best = tied.maxOf { values[it] ?: 0 }
            tied = tied.filter { (values[it] ?: 0) == best }
            if (tied.size == 1) return Outcome.Winner(tied.single(), criterion)
        }
        return Outcome.Draw
    }

    /** Per-team numbers for the clock tiebreak chain (used only after an exact pillar HP tie). */
    public data class Tiebreak(val damageDealt: Map<TeamId, Int>, val kills: Map<TeamId, Int>) {
        public companion object {
            public val NONE: Tiebreak = Tiebreak(emptyMap(), emptyMap())
        }
    }
}
