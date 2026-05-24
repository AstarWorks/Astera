package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.Nexus
import com.astarworks.astera.core.domain.nexus.NexusDamageOutcome
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.nexus.NexusState

/**
 * Per-match aggregate state for an Annihilation match (ADR-0108,
 * Wave 5annihilation).
 *
 * Owns:
 *
 *  - The set of [Nexus] pillars (3 per team × N teams) keyed by [NexusId].
 *  - The current [NexusPhase] (build / engage / tier3 / sudden-death).
 *  - The set of eliminated teams (once a team's Tier 3 pillar dies the
 *    team is added to this set and stays there for the match's lifetime).
 *  - The phase-entered tick (so the tracker can decide "is the timer up?").
 *
 * Pure domain — no I/O, no events. The tracker
 * (`match.application.gamemode.annihilation.AnnihilationMatchTracker`) is
 * responsible for publishing events on every mutation result.
 *
 * ## Mutations
 *
 * Every mutation returns a new `AnnihilationMatchState` instance + a typed
 * outcome describing what changed. Callers ignore mutations that report
 * `Ignored` outcomes; events fire only on `Damaged` / `Destroyed` / etc.
 *
 * ## Why one giant aggregate?
 *
 * Annihilation's win condition is a cross-team predicate ("≤1 surviving
 * Tier 3") + the phase transition is a global-to-match decision ("any team
 * lost both Tier 1 + 2 → enter Phase 3"). Splitting per-team would force
 * the tracker to recompute these on every team mutation; co-locating the
 * full state lets the win-condition / phase-advance logic stay
 * declarative.
 */
public data class AnnihilationMatchState(
    public val matchId: MatchId,
    public val config: AnnihilationConfig,
    public val phase: NexusPhase,
    public val phaseEnteredAtTick: Long,
    public val pillars: Map<NexusId, Nexus>,
    public val eliminatedTeams: Set<TeamId>,
) {

    init {
        require(pillars.isNotEmpty()) { "AnnihilationMatchState requires >=1 pillar (matchId=${matchId.value})" }
        // Sanity: every pillar's tier3 owning team that is alive should not appear in eliminatedTeams.
        // Allow exception during construction (caller may seed an eliminated team with a destroyed tier3).
    }

    /**
     * Snapshot of teams currently participating, derived from pillar
     * ownership. Order is insertion-ordered from the underlying map.
     */
    public val allTeams: Set<TeamId>
        get() = pillars.values.map { it.owningTeam }.toSet()

    /** Teams that have NOT yet been eliminated (Tier 3 still alive). */
    public val survivingTeams: Set<TeamId>
        get() = allTeams - eliminatedTeams

    /**
     * Pillars belonging to [team], ordered Tier 1 → 2 → 3.
     */
    public fun pillarsOf(team: TeamId): List<Nexus> =
        pillars.values
            .filter { it.owningTeam == team }
            .sortedBy { it.tier.ordinalValue }

    /**
     * Whether [team] has lost both Tier 1 and Tier 2 pillars. Used by the
     * Phase 2 → Phase 3 transition rule.
     */
    public fun hasLostBothLowerTiers(team: TeamId): Boolean {
        val own = pillarsOf(team)
        val t1Dead = own.firstOrNull { it.tier == NexusPillarTier.TIER_1 }?.isDestroyed ?: false
        val t2Dead = own.firstOrNull { it.tier == NexusPillarTier.TIER_2 }?.isDestroyed ?: false
        return t1Dead && t2Dead
    }

    /**
     * Apply [amount] damage to the pillar at [nexusId]. Returns the new
     * match state + the outcome (the same outcome the underlying
     * [Nexus.applyDamage] returned, or [NexusDamageOutcome.Ignored] with
     * the [com.astarworks.astera.core.domain.nexus.IgnoreReason.ALREADY_DESTROYED]
     * reason if [nexusId] is unknown — defensive against stale ids).
     *
     * Mutation discipline: the caller already-aware of phase rules calls
     * this; the state itself does not enforce "you can only damage Tier 1
     * in Phase 2" beyond what [Nexus.applyDamage] already enforces (a Tier
     * 2 pillar in [NexusState.BUILDING] is no-op'd). The phase-gate
     * decisions live in the tracker.
     */
    public fun applyDamage(nexusId: NexusId, amount: Int): Pair<AnnihilationMatchState, NexusDamageOutcome> {
        val pillar = pillars[nexusId]
            ?: return this to com.astarworks.astera.core.domain.nexus.NexusDamageOutcome.Ignored(
                reason = com.astarworks.astera.core.domain.nexus.IgnoreReason.ALREADY_DESTROYED,
            )
        val (next, outcome) = pillar.applyDamage(amount)
        if (outcome is NexusDamageOutcome.Ignored) return this to outcome
        val newPillars = pillars + (nexusId to next)
        val newEliminated = if (outcome is NexusDamageOutcome.Destroyed && next.tier == NexusPillarTier.TIER_3) {
            eliminatedTeams + next.owningTeam
        } else {
            eliminatedTeams
        }
        return copy(pillars = newPillars, eliminatedTeams = newEliminated) to outcome
    }

    /**
     * Promote every pillar at [tier] from [NexusState.BUILDING] to
     * [NexusState.VULNERABLE]. No-op for pillars already past Building.
     */
    public fun openTierForCombat(tier: NexusPillarTier): AnnihilationMatchState {
        val updated = pillars.mapValues { (_, p) ->
            if (p.tier == tier) p.openForCombat() else p
        }
        return copy(pillars = updated)
    }

    /**
     * Advance to [next] phase, stamping the entry tick. The caller should
     * use [AnnihilationPhaseRules] to decide *whether* to advance; this
     * method assumes the decision is already made.
     */
    public fun enterPhase(next: NexusPhase, atTick: Long): AnnihilationMatchState =
        copy(phase = next, phaseEnteredAtTick = atTick)

    public companion object {

        /**
         * Seed initial state for [matchId] with [config] over [teams].
         *
         * Generates `3 × teams.size` pillars (one per [NexusPillarTier] per
         * team), all in [NexusState.BUILDING] at full HP. The phase is
         * [NexusPhase.Phase1Build] entered at tick `0`.
         *
         * The pillar ids follow `<match>-<team>-<tier>` so audit / SQL /
         * compass HUD can deterministically reconstruct them without a
         * lookup. The tier number uses [NexusPillarTier.ordinalValue]
         * (1/2/3).
         */
        public fun seed(
            matchId: MatchId,
            config: AnnihilationConfig,
            teams: Collection<TeamId>,
            position: (TeamId, NexusPillarTier) -> com.astarworks.astera.core.domain.nexus.INexusEntity.Position3D =
                { _, _ -> com.astarworks.astera.core.domain.nexus.INexusEntity.Position3D(0, 64, 0) },
            worldKey: String = matchId.value,
        ): AnnihilationMatchState {
            require(teams.isNotEmpty()) { "AnnihilationMatchState.seed requires >=1 team (matchId=${matchId.value})" }
            val pillars = buildMap {
                teams.forEach { team ->
                    NexusPillarTier.ordered().forEach { tier ->
                        val id = NexusId("${matchId.value}-${team.value}-${tier.ordinalValue}")
                        put(
                            id,
                            Nexus(
                                id = id,
                                matchId = matchId,
                                owningTeam = team,
                                tier = tier,
                                maxHp = config.pillarHp,
                                hp = config.pillarHp,
                                state = NexusState.BUILDING,
                            ),
                        )
                    }
                }
            }
            // The position / worldKey are part of the entity layer; we keep
            // the state pure here. The tracker materialises INexusEntity
            // separately on top of this state.
            @Suppress("UNUSED_VARIABLE") val unusedPos = position
            @Suppress("UNUSED_VARIABLE") val unusedWorld = worldKey
            return AnnihilationMatchState(
                matchId = matchId,
                config = config,
                phase = NexusPhase.Phase1Build,
                phaseEnteredAtTick = 0L,
                pillars = pillars,
                eliminatedTeams = emptySet(),
            )
        }
    }
}
