package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned
import com.astarworks.astera.core.domain.event.NexusDamaged
import com.astarworks.astera.core.domain.event.NexusDestroyed
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.Nexus
import com.astarworks.astera.core.domain.nexus.NexusDamageOutcome
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.nexus.NexusState
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StagePillar
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationMatchState
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationPhaseRules
import com.astarworks.astera.match.domain.gamemode.annihilation.WinConditionRules

/**
 * Per-match Annihilation tracker (ADR-0108, Wave 5annihilation).
 *
 * Owns the in-memory [AnnihilationMatchState] map keyed by match id, applies
 * damage / phase advances / win-condition checks, and publishes the
 * resulting domain events through [IDomainEventBus].
 *
 * ## Threading
 *
 * Single-threaded by contract — all entry points are invoked from the Paper
 * main thread (via `AnnihilationGameMode`'s lifecycle hooks). The mutable
 * map is therefore unprotected; tests construct a fresh tracker per case.
 *
 * ## Why this lives in `application/gamemode/annihilation/`
 *
 * It's the use-case-layer composition of the pure-domain
 * `AnnihilationMatchState` + the cross-cutting `IDomainEventBus`. Following
 * the established Wave 5c convention (e.g. `CarryTntListener` lives in
 * `match.application.usecase.mode/`), tracker-style services hang off the
 * application layer; the domain stays pure.
 *
 * ## Hook surface
 *
 *  - [register] — seed a new match. Called from `AnnihilationGameMode.onMatchStarted`.
 *  - [unregister] — discard match. Called from `AnnihilationGameMode.onMatchEnded`.
 *  - [recordDamage] — apply damage from a Paper-side hit listener. Public
 *    so a Phase 6 `PaperNexusBlockListener` can call without going through
 *    the bus.
 *  - [advancePhasesIfNeeded] — called from `AnnihilationGameMode.onTick`.
 *  - [evaluateWinCondition] — called from both `onTick` (sudden-death timer)
 *    and after every `recordDamage` (elimination detection).
 *
 *  The four public read methods ([state], [allActive], [scoresOf],
 *  [phaseOf]) let other systems (HUD, audit, persistence projection) read
 *  state without mutating.
 */
public class AnnihilationMatchTracker(
    private val eventBus: IDomainEventBus,
) {

    private val matches: MutableMap<MatchId, AnnihilationMatchState> = mutableMapOf()

    /** Where each match's pillars stand (from the stage), by pillar id. Empty when the stage declares none. */
    private val sites: MutableMap<MatchId, PillarSites> = mutableMapOf()

    /** A match's pillar sites: the world they are in and the stage entry of each pillar. */
    public data class PillarSites(val world: WorldId, val byNexus: Map<NexusId, StagePillar>)

    /** A standing-or-fallen pillar together with its world site (null when the stage declared none). */
    public data class PillarView(val nexus: Nexus, val site: StagePillar?, val world: WorldId?)

    /** Every pillar of [matchId] with its site; empty for an unknown match. */
    public fun pillarViews(matchId: MatchId): List<PillarView> {
        val state = matches[matchId] ?: return emptyList()
        val placed = sites[matchId]
        return state.pillars.values.map { PillarView(it, placed?.byNexus?.get(it.id), placed?.world) }
    }

    /** Sites of [matchId], or null when none were registered. */
    public fun sitesOf(matchId: MatchId): PillarSites? = sites[matchId]

    /** Currently-tracked match ids — order undefined. */
    public fun allActive(): Set<MatchId> = matches.keys.toSet()

    /** Current state snapshot or null if [matchId] is not registered. */
    public fun state(matchId: MatchId): AnnihilationMatchState? = matches[matchId]

    /** Current phase of [matchId] or null if not registered. */
    public fun phaseOf(matchId: MatchId): NexusPhase? = matches[matchId]?.phase

    /**
     * Seed a new Annihilation match. Idempotent — re-registering an existing
     * match id replaces the prior state (this matches the framework's
     * `IGameModeScheduler.register` contract).
     *
     * Returns the seeded state for the caller's convenience.
     */
    public fun register(
        matchId: MatchId,
        config: AnnihilationConfig,
        teams: Collection<TeamId>,
        atTick: Long = 0L,
        world: WorldId? = null,
        pillarSites: List<StagePillar> = emptyList(),
    ): AnnihilationMatchState {
        val teamSet = teams.toSet()
        val placed = pillarSites.filter { it.team in teamSet }
        val seeded = AnnihilationMatchState.seed(
            matchId,
            config,
            teams,
            hpOverrides = placed.mapNotNull { p -> p.hp?.let { (p.team to p.tier) to it } }.toMap(),
        ).copy(phaseEnteredAtTick = atTick)
        matches[matchId] = seeded
        if (world != null && placed.isNotEmpty()) {
            sites[matchId] = PillarSites(
                world,
                placed.associateBy { NexusId("${matchId.value}-${it.team.value}-${it.tier.ordinalValue}") },
            )
        } else {
            sites.remove(matchId)
        }
        return seeded
    }

    /** Discard tracking state for [matchId]. No-op if unknown. */
    public fun unregister(matchId: MatchId) {
        matches.remove(matchId)
        sites.remove(matchId)
    }

    /**
     * Apply [amount] damage to [nexusId] in [matchId]. Returns the outcome
     * (the same outcome the underlying [AnnihilationMatchState.applyDamage]
     * returned, or [NexusDamageOutcome.Ignored] with reason
     * [com.astarworks.astera.core.domain.nexus.IgnoreReason.ALREADY_DESTROYED]
     * if [matchId] is unknown).
     *
     * Side-effects on a non-ignored outcome:
     *  - One [NexusDamaged] event (non-lethal) OR one [NexusDestroyed] event
     *    (lethal). Never both for the same hit.
     *  - If the destroyed pillar is Tier 3, one additional [TeamEliminated]
     *    event for the owning team (idempotent — already-eliminated teams
     *    skip).
     *
     * Note: [attackerId] may be null when damage is environmental (sudden-
     * death DoT, admin command, etc.).
     */
    public fun recordDamage(
        matchId: MatchId,
        nexusId: NexusId,
        amount: Int,
        attackerId: PlayerId?,
        atTick: Long,
    ): NexusDamageOutcome {
        val state = matches[matchId] ?: return NexusDamageOutcome.Ignored(
            reason = com.astarworks.astera.core.domain.nexus.IgnoreReason.ALREADY_DESTROYED,
        )
        val priorPillar = state.pillars[nexusId] ?: return NexusDamageOutcome.Ignored(
            reason = com.astarworks.astera.core.domain.nexus.IgnoreReason.ALREADY_DESTROYED,
        )
        val (next, outcome) = state.applyDamage(nexusId, amount)
        matches[matchId] = next

        when (outcome) {
            is NexusDamageOutcome.Ignored -> Unit
            is NexusDamageOutcome.Damaged -> {
                eventBus.publish(
                    NexusDamaged(
                        matchId = matchId,
                        nexusId = nexusId,
                        owningTeam = priorPillar.owningTeam,
                        tier = priorPillar.tier,
                        attackerId = attackerId,
                        damageAmount = amount,
                        previousHp = outcome.previousHp,
                        currentHp = outcome.currentHp,
                        atTick = atTick,
                    ),
                )
            }
            is NexusDamageOutcome.Destroyed -> {
                eventBus.publish(
                    NexusDestroyed(
                        matchId = matchId,
                        nexusId = nexusId,
                        owningTeam = priorPillar.owningTeam,
                        tier = priorPillar.tier,
                        attackerId = attackerId,
                        previousHp = outcome.previousHp,
                        atTick = atTick,
                    ),
                )
                // Tier 3 destruction always carries a team elimination.
                // AnnihilationMatchState.applyDamage already added the team
                // to eliminatedTeams as part of the same transition; we just
                // surface the cross-plugin signal here.
                if (priorPillar.tier == NexusPillarTier.TIER_3) {
                    eventBus.publish(
                        TeamEliminated(
                            matchId = matchId,
                            teamId = priorPillar.owningTeam,
                            survivingPlayers = emptySet(),
                            atTick = atTick,
                        ),
                    )
                }
            }
        }
        return outcome
    }

    /**
     * Advance the phase of [matchId] if [AnnihilationPhaseRules] says it's
     * time. Returns the new phase or null if no transition fired.
     *
     * Side-effect: publishes one [AnnihilationPhaseTransitioned] event on
     * advance + opens the appropriate tier for combat:
     *
     *  - Phase 2 entered → [NexusPillarTier.TIER_1] pillars open.
     *  - Phase 3 entered → [NexusPillarTier.TIER_2] + [NexusPillarTier.TIER_3]
     *    pillars open (per ADR-0108: Tier 2 unlocks at Phase 2 lower-cascade
     *    OR at Phase 3 timer expiry; for simplicity Phase 3 entry guarantees
     *    every remaining pillar is [NexusState.VULNERABLE]).
     *
     *  Phase 4 entry has no tier-opening side-effect (all already open).
     */
    public fun advancePhasesIfNeeded(matchId: MatchId, currentTick: Long): NexusPhase? {
        val state = matches[matchId] ?: return null
        val nextPhase = AnnihilationPhaseRules.evaluate(state, currentTick) ?: return null
        val oldPhase = state.phase
        var advanced = state.enterPhase(nextPhase, currentTick)
        // Open tiers as required.
        when (nextPhase) {
            NexusPhase.Phase2Engage -> {
                advanced = advanced.openTierForCombat(NexusPillarTier.TIER_1)
            }
            NexusPhase.Phase3Tier3 -> {
                advanced = advanced.openTierForCombat(NexusPillarTier.TIER_2)
                advanced = advanced.openTierForCombat(NexusPillarTier.TIER_3)
            }
            NexusPhase.Phase4SuddenDeath -> Unit
            NexusPhase.Phase1Build -> Unit
        }
        matches[matchId] = advanced
        eventBus.publish(
            AnnihilationPhaseTransitioned(
                matchId = matchId,
                oldPhase = oldPhase,
                newPhase = nextPhase,
                atTick = currentTick,
            ),
        )
        return nextPhase
    }

    /**
     * Evaluate the current win condition for [matchId]. Returns the outcome
     * without mutating tracker state — the caller (`AnnihilationGameMode`)
     * decides what to do on a non-`InProgress` outcome (typically: end the
     * match via `IMatchLifecycle` — wired in Wave 6 paper adapter).
     */
    public fun evaluateWinCondition(matchId: MatchId, currentTick: Long): WinConditionRules.Outcome {
        val state = matches[matchId] ?: return WinConditionRules.Outcome.InProgress
        return WinConditionRules.evaluate(state, currentTick)
    }

    /**
     * Score snapshot for every team currently tracked in [matchId]. Empty
     * list if the match id is unknown. Returned list is sorted leader-first
     * (per [com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationScore]'s
     * comparable contract).
     */
    public fun scoresOf(matchId: MatchId): List<com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationScore> {
        val state = matches[matchId] ?: return emptyList()
        return com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationScore.computeAll(
            state.allTeams,
            state.pillars.values,
        )
    }

    /**
     * Apply sudden-death DoT to every surviving Tier 3 pillar of [matchId].
     * Returns the list of outcomes (one per Tier 3 still in
     * [NexusState.VULNERABLE]).
     *
     * Called from `AnnihilationGameMode.onTick` when the match's current
     * phase is [NexusPhase.Phase4SuddenDeath].
     */
    public fun applySuddenDeathTick(matchId: MatchId, currentTick: Long): List<NexusDamageOutcome> {
        val state = matches[matchId] ?: return emptyList()
        if (state.phase != NexusPhase.Phase4SuddenDeath) return emptyList()
        val tier3Ids = state.pillars.values
            .filter { it.tier == NexusPillarTier.TIER_3 && it.state == NexusState.VULNERABLE }
            .map { it.id }
        return tier3Ids.map { id ->
            recordDamage(matchId, id, state.config.suddenDeathDotPerTick, attackerId = null, atTick = currentTick)
        }
    }
}
