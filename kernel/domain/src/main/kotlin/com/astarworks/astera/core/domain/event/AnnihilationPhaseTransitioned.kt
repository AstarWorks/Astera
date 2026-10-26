package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import java.time.Instant

/**
 * Cross-plugin event (ADR-0108, Wave 5annihilation): the Annihilation phase
 * machine advanced.
 *
 * # When fired
 *
 * `AnnihilationMatchTracker` on every observed phase transition. The
 * transition itself is driven by:
 *
 *  - The phase timer expiring ([com.astarworks.astera.core.domain.nexus.NexusPhase.Phase1Build]
 *    → [com.astarworks.astera.core.domain.nexus.NexusPhase.Phase2Engage] at
 *    default `build.duration_seconds` from `annihilation.yaml`).
 *  - HP-threshold conditions ([com.astarworks.astera.core.domain.nexus.NexusPhase.Phase2Engage]
 *    → [com.astarworks.astera.core.domain.nexus.NexusPhase.Phase3Tier3] once
 *    a team has lost both Tier 1 + Tier 2).
 *  - The sudden-death timer trigger ([com.astarworks.astera.core.domain.nexus.NexusPhase.Phase3Tier3]
 *    → [com.astarworks.astera.core.domain.nexus.NexusPhase.Phase4SuddenDeath]).
 *
 * # Subscribers
 *
 * - HUD: phase-banner overlay + per-phase debuff hints.
 * - AnnihilationGameMode: publishes the framework-level GameMode events
 *   independently (this is the Annihilation-specific axis; the framework
 *   axis is generic).
 * - Audit log: `annihilation.phase.transitioned`.
 *
 * # Why a separate event from `match.domain.event.PhaseChanged`?
 *
 * `PhaseChanged` describes the generic 5-state match state machine
 * (`WAITING`/`COUNTDOWN`/`ACTIVE`/`SUDDEN_DEATH`/`ENDED`). Annihilation has
 * 4 internal phases that all live inside `MatchPhase.ACTIVE` (build /
 * engage / tier3 are all "ACTIVE" from the state-machine's POV; only
 * `Phase4SuddenDeath` maps to `SUDDEN_DEATH`). The Annihilation-specific
 * phase signal needs its own event so HUD / audit can render the *internal*
 * mode-phase distinction without re-deriving from match HP state.
 */
public data class AnnihilationPhaseTransitioned(
    public val matchId: MatchId,
    public val oldPhase: NexusPhase,
    public val newPhase: NexusPhase,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(oldPhase != newPhase) {
            "AnnihilationPhaseTransitioned requires distinct phases (got both=${newPhase.id})"
        }
    }
}
