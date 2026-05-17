package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.domain.match.PhaseTimings

/**
 * Wave 4.5 (R3.5, ADR-0027): pure function that determines the winning team
 * of a [Match] based on the current score map.
 *
 * Stateless and dependency-free — kept as a class rather than an `object` so
 * Phase C can swap in a more elaborate evaluator (e.g. capture-points,
 * objective-weighted scoring) via constructor injection without touching
 * call sites.
 *
 * Decision rules (kept intentionally simple for Phase 2):
 * - Find the team with the highest score.
 * - If that score is `< SCORE_THRESHOLD` → no winner (`null`).
 * - If two or more teams are tied for highest at or above the threshold →
 *   no clear winner (`null`); caller may treat as a draw or fall back to
 *   sudden-death rules.
 *
 * Intended integration site: [EndMatchUseCase] will call `evaluate(match)`
 * before publishing [com.astarworks.astera.match.domain.event.MatchEnded] so
 * the `winningTeam` field is populated for `ScoreThresholdReached` /
 * `SuddenDeathResolved` end reasons. See "WIRE NOTES FOR PHASE C" in the
 * R3.5 plan.
 */
public class VictoryConditionEvaluator {

    /**
     * Returns the [TeamId] of the unique winning team, or `null` if either
     * (a) no team has reached [PhaseTimings.SCORE_THRESHOLD], or
     * (b) the top score is tied between multiple teams.
     */
    public fun evaluate(match: Match): TeamId? {
        val perTeam: Map<TeamId, Int> = match.score.perTeam
        val maxScore = perTeam.values.maxOrNull() ?: return null
        if (maxScore < PhaseTimings.SCORE_THRESHOLD) return null

        val leaders = perTeam.entries.filter { it.value == maxScore }
        return if (leaders.size == 1) leaders.first().key else null
    }
}
