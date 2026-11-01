package com.astarworks.astera.core.domain.nexus

/**
 * Lifecycle phases of an Astera Annihilation match (ADR-0108, reshaped by ADR-0319).
 *
 * Lives in `core.domain.nexus.*` rather than `match.domain.gamemode.*`
 * because the phase is consumed by Core domain events
 * ([com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned])
 * — Core can't import from Match.
 *
 * Five phases, Shotbow-style (lengths and multipliers live in `content/game-modes/annihilation.yaml`;
 * defaults: 6 / 8 / 8 / 6 min + at most 5 min of sudden death):
 *
 *  1. **[Phase1Build]** — pillars are [NexusState.BUILDING] (immune). Base ores are open, the Mid is closed.
 *  2. **[Phase2Engage]** — Tier 1 pillars open ([NexusState.VULNERABLE]); the Mid outer ring (gold, diamond) opens.
 *  3. **[Phase3Center]** — Tier 2 + 3 pillars open; the Mid core (emerald) opens.
 *  4. **[Phase4Escalation]** — pillar damage is multiplied (default x1.5).
 *  5. **[Phase5SuddenDeath]** — pillar damage multiplied again (default x2) and every pillar bleeds HP on an
 *     interval down to a floor; on expiry the HP tiebreak decides.
 *
 * Which Mid ore opens in which phase is authored per ore-node file (`phase_gates`), keyed by [number].
 *
 * ## Relation to [com.astarworks.astera.core.domain.match.MatchPhase]
 *
 * `MatchPhase` is the generic match state machine (`WAITING`/`COUNTDOWN`/`ACTIVE`/`SUDDEN_DEATH`/`ENDED`);
 * `NexusPhase` is Annihilation-specific and runs inside `ACTIVE`.
 */
public sealed interface NexusPhase {

    /** Stable string id for logging / persistence / i18n keys. */
    public val id: String

    /** 1-based position in [ordered]; the value `phase_gates` in ore-node files uses. */
    public val number: Int

    /** Phase 1: pillars immune, Mid closed. */
    public data object Phase1Build : NexusPhase {
        override val id: String = "build"
        override val number: Int = 1
    }

    /** Phase 2: Tier 1 pillars vulnerable, Mid outer ring open. */
    public data object Phase2Engage : NexusPhase {
        override val id: String = "engage"
        override val number: Int = 2
    }

    /** Phase 3: Tier 2 + 3 pillars vulnerable, Mid core open. */
    public data object Phase3Center : NexusPhase {
        override val id: String = "center"
        override val number: Int = 3
    }

    /** Phase 4: pillar damage multiplied. */
    public data object Phase4Escalation : NexusPhase {
        override val id: String = "escalation"
        override val number: Int = 4
    }

    /** Phase 5: larger damage multiplier + pillar bleed; timer expiry triggers the HP tiebreak. */
    public data object Phase5SuddenDeath : NexusPhase {
        override val id: String = "sudden-death"
        override val number: Int = 5
    }

    public companion object {

        /** Canonical Phase 1 -> Phase 5 ordering. */
        public fun ordered(): List<NexusPhase> = listOf(
            Phase1Build, Phase2Engage, Phase3Center, Phase4Escalation, Phase5SuddenDeath,
        )

        /** Resolve a phase from its [NexusPhase.id]; null on unknown input. */
        public fun byId(id: String): NexusPhase? =
            ordered().firstOrNull { it.id == id }

        /** Resolve a phase from its [NexusPhase.number]; null on unknown input. */
        public fun byNumber(number: Int): NexusPhase? =
            ordered().firstOrNull { it.number == number }

        /** Linear forward-only successor. Returns null at [Phase5SuddenDeath] (the match ends from there). */
        public fun next(phase: NexusPhase): NexusPhase? = when (phase) {
            Phase1Build -> Phase2Engage
            Phase2Engage -> Phase3Center
            Phase3Center -> Phase4Escalation
            Phase4Escalation -> Phase5SuddenDeath
            Phase5SuddenDeath -> null
        }
    }
}
