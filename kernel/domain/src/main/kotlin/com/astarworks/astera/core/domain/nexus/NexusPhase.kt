package com.astarworks.astera.core.domain.nexus

/**
 * Lifecycle phases of an Astera Annihilation match (ADR-0108, Wave 5annihilation).
 *
 * Lives in `core.domain.nexus.*` rather than `match.domain.gamemode.*`
 * because the phase enum is consumed by Core domain events
 * ([com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned])
 * — Core can't import from Match.
 *
 * Hypixel Annihilation has 5 phases (Phase 1 build / Phase 2 spread / Phase 3
 * tier-2 weapons / Phase 4 nexus exposed / Phase 5 sudden death). Astera
 * collapses to **4 phases** that map cleanly onto the 3-pillar structure +
 * a final sudden-death window:
 *
 *  1. **[Phase1Build]** — Pillars are [NexusState.BUILDING], immune to
 *     damage. Players gather resources (Star + per-weapon Ammo/Stamina/Mana)
 *     and set up defenses. Default duration: 5 minutes (configurable via
 *     `content/game-modes/annihilation.yaml`).
 *  2. **[Phase2Engage]** — All Tier 1 pillars open ([NexusState.VULNERABLE]).
 *     Standard combat phase. Default duration: 10 minutes. May exit early
 *     if any team loses its Tier 1 + Tier 2 pillars (cascades to Phase 3).
 *  3. **[Phase3Tier3]** — Tier 3 pillars (the Nexus proper) unlock. Killing
 *     these eliminates the owning team. The phase exit condition is
 *     "at most one team has a surviving Tier 3" — that team wins.
 *  4. **[Phase4SuddenDeath]** — Final 60-second window (configurable). All
 *     remaining Tier 3 pillars take damage-over-time so a stalled match
 *     forces a resolution. If the timer expires with multiple teams alive,
 *     the team with the most aggregate surviving HP wins (tiebreaker:
 *     fewest pillars destroyed; final tiebreaker: draw).
 *
 * ## Why a sealed interface (not enum)?
 *
 * Phase 2+ may introduce per-phase typed parameters (e.g. `Phase4SuddenDeath`
 * carries the configured DoT rate; `Phase3Tier3` carries the surviving-team
 * threshold). A sealed interface keeps the phase-id surface stable while
 * letting per-phase config live on its own subclass. Phase 1 PoC ships the
 * four bare data objects — config lives in `AnnihilationConfig` (Match
 * domain) and the phase variants stay parameterless until a real need
 * surfaces.
 *
 * ## Relation to [com.astarworks.astera.core.domain.match.MatchPhase]
 *
 * The Core `MatchPhase` enum is the generic match-state-machine vocabulary
 * (`WAITING`/`COUNTDOWN`/`ACTIVE`/`SUDDEN_DEATH`/`ENDED`). Annihilation maps:
 *
 *   MatchPhase                | NexusPhase
 *   --------------------------+------------------------------
 *   WAITING / COUNTDOWN        | (pre-match; no NexusPhase)
 *   ACTIVE (first 5 min)       | Phase1Build
 *   ACTIVE (5–15 min)          | Phase2Engage
 *   ACTIVE (15 min+)           | Phase3Tier3
 *   SUDDEN_DEATH               | Phase4SuddenDeath
 *   ENDED                      | (post-match; no NexusPhase)
 *
 * The two enums coexist because `MatchPhase` is global (every match mode
 * runs through the state machine; Norma uses it too) and `NexusPhase` is
 * Annihilation-specific. The match-side `AnnihilationGameMode` translates
 * between them at the [IGameMode] hooks.
 */
public sealed interface NexusPhase {

    /** Stable string id for logging / persistence / YAML. */
    public val id: String

    /**
     * Phase 1: build window. Pillars [NexusState.BUILDING]; immune to damage.
     * Players gather Star + resources and set up base defenses.
     */
    public data object Phase1Build : NexusPhase {
        override val id: String = "build"
    }

    /**
     * Phase 2: standard combat. Tier 1 pillars open
     * ([NexusState.VULNERABLE]); Tier 2/3 remain in [NexusState.BUILDING].
     */
    public data object Phase2Engage : NexusPhase {
        override val id: String = "engage"
    }

    /**
     * Phase 3: Tier 3 pillars unlock. Killing a team's Tier 3 eliminates it.
     */
    public data object Phase3Tier3 : NexusPhase {
        override val id: String = "tier3"
    }

    /**
     * Phase 4: sudden-death window. All surviving Tier 3 pillars take
     * configurable DoT; timer expiry triggers HP-based tiebreaker.
     */
    public data object Phase4SuddenDeath : NexusPhase {
        override val id: String = "sudden-death"
    }

    public companion object {

        /** Canonical Phase 1 → Phase 4 ordering. */
        public fun ordered(): List<NexusPhase> = listOf(
            Phase1Build, Phase2Engage, Phase3Tier3, Phase4SuddenDeath,
        )

        /** Resolve a phase from its [NexusPhase.id]; null on unknown input. */
        public fun byId(id: String): NexusPhase? =
            ordered().firstOrNull { it.id == id }

        /**
         * Linear forward-only successor. Returns null at [Phase4SuddenDeath]
         * (no successor — match ends from there).
         */
        public fun next(phase: NexusPhase): NexusPhase? = when (phase) {
            Phase1Build -> Phase2Engage
            Phase2Engage -> Phase3Tier3
            Phase3Tier3 -> Phase4SuddenDeath
            Phase4SuddenDeath -> null
        }
    }
}
