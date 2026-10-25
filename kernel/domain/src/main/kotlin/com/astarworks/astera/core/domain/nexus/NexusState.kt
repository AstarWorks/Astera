package com.astarworks.astera.core.domain.nexus

/**
 * Lifecycle state of a single [Nexus] pillar (ADR-0108, Wave 5annihilation).
 *
 * Linear progression: [Building] → [Vulnerable] → [Destroyed]. No back-edge
 * (a destroyed pillar does not regenerate — Phase 1 PoC scope).
 *
 *  - [Building]: Phase 1 "Build" window. Pillar HP is at max; no damage may
 *    be dealt (defensive: attempts are no-op'd in the domain regardless of
 *    whether the listener cancels the block break). Lasts until the global
 *    `AnnihilationPhase.Build` timer expires (default 5 minutes per YAML).
 *  - [Vulnerable]: Pillar may be damaged. HP decrements via [Nexus.applyDamage].
 *    The owning team's defenders are expected to hold; attackers earn one
 *    `NexusDamaged` event per hit and one `NexusDestroyed` event when HP
 *    hits zero.
 *  - [Destroyed]: HP == 0. The pillar's debuffs apply to the owning team
 *    (Tier 1 → respawn delay; Tier 2 → resource regen reduction; Tier 3 →
 *    team elimination via [AnnihilationGameMode]). Subsequent damage
 *    attempts are no-op.
 *
 * The pillar tier's debuff effect is *not* part of [NexusState] — the state
 * machine here just tracks "can this take damage / is it gone". The debuff
 * application lives in the match-domain `AnnihilationMatchTracker` so that
 * the Nexus value object stays a pure data carrier.
 */
public enum class NexusState {

    /** Pre-engagement; immune to damage. Phase 1 "Build" window. */
    BUILDING,

    /** Engagement open; takes damage until HP == 0. */
    VULNERABLE,

    /** HP exhausted; no further damage applies. Team debuff is in effect. */
    DESTROYED,
    ;
}
