package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.Nexus
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.nexus.NexusState

/**
 * Per-team aggregate scoring snapshot for an Annihilation match (ADR-0108,
 * Wave 5annihilation).
 *
 * Score = sum of own-team Nexus HP — higher is better. Three derived
 * tiebreakers are exposed so the sudden-death HP-tiebreaker can resolve
 * deterministically without a runoff:
 *
 *  - **score** (primary): aggregate HP across all surviving own-team
 *    pillars.
 *  - **pillarsAlive** (secondary): count of own-team pillars still
 *    [NexusState.BUILDING] or [NexusState.VULNERABLE].
 *  - **tier3Alive** (tertiary): whether this team's Tier 3 (Nexus proper)
 *    is alive. A team whose Tier 3 fell is conceptually out of the running
 *    regardless of any cosmetic HP on lower tiers (which shouldn't be
 *    possible per the tier-ordering invariant, but guarded against bad
 *    state regardless).
 *
 * ## Comparison
 *
 * Implements [Comparable] — higher score sorts first (so
 * `scores.sortedDescending().first()` is the leader). The two tiebreakers
 * apply in turn; a true draw (identical score + same pillarsAlive + same
 * tier3Alive) returns 0 from [compareTo], which the win-condition resolver
 * treats as a draw.
 *
 * ## Immutability
 *
 * Pure data — recomputed from scratch each evaluation. The tracker does
 * not retain a running cache; per-tick reads against the in-memory pillar
 * map are cheap (3 teams × 3 pillars = 9 reads max).
 */
public data class AnnihilationScore(
    public val team: TeamId,
    public val score: Int,
    public val pillarsAlive: Int,
    public val tier3Alive: Boolean,
) : Comparable<AnnihilationScore> {

    override fun compareTo(other: AnnihilationScore): Int {
        // Primary: higher score sorts first → negate the natural order.
        val byScore = other.score.compareTo(this.score)
        if (byScore != 0) return byScore
        val byPillars = other.pillarsAlive.compareTo(this.pillarsAlive)
        if (byPillars != 0) return byPillars
        // Tier 3 alive trumps not — true > false → invert.
        return other.tier3Alive.compareTo(this.tier3Alive)
    }

    public companion object {

        /**
         * Compute the score for [team] across [pillars]. The input may
         * include other teams' pillars — they are filtered out.
         *
         * Pillars in [NexusState.DESTROYED] contribute 0 to score and 0 to
         * pillarsAlive. Pillars in [NexusState.BUILDING] contribute full HP
         * (since they are technically alive — Phase 1 immune-but-alive).
         */
        public fun compute(team: TeamId, pillars: Collection<Nexus>): AnnihilationScore {
            val own = pillars.filter { it.owningTeam == team }
            val score = own.sumOf { if (it.state == NexusState.DESTROYED) 0 else it.hp }
            val alive = own.count { it.state != NexusState.DESTROYED }
            val tier3 = own.firstOrNull { it.tier == NexusPillarTier.TIER_3 }
                ?.let { it.state != NexusState.DESTROYED } ?: false
            return AnnihilationScore(
                team = team,
                score = score,
                pillarsAlive = alive,
                tier3Alive = tier3,
            )
        }

        /**
         * Compute scores for every team in [teams] over the same [pillars]
         * snapshot. Returned list is sorted leader-first.
         */
        public fun computeAll(teams: Collection<TeamId>, pillars: Collection<Nexus>): List<AnnihilationScore> =
            teams.map { compute(it, pillars) }.sorted()
    }
}
