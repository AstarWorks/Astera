package com.astarworks.astera.core.domain.mob

/**
 * Vendor-neutral AI behaviour hint carried on a [MobSpec] (Wave 5pve,
 * ADR-0085).
 *
 * The hint is **advisory** — each [com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter]
 * implementation decides which hints it can honour through
 * [com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter.supportsAi].
 * The Minimal adapter only supports [Default] and [Stationary]; richer
 * adapters (MythicMobs / Paper-side pathfinder, Wave 6b) honour every variant.
 *
 * # Why an enum, not a sealed class
 *
 * AI shape per Phase 1 is intentionally coarse — the goal is "tell the
 * adapter which behaviour family to use". Phase 4 may swap this for a more
 * structured `MobAiSpec` (target priority, leash range, retreat threshold)
 * — that ADR will supersede ADR-0085 §"MobAiHint" rather than evolve the
 * enum in place.
 */
public enum class MobAiHint {
    /** Adapter default — typically vanilla mob AI for the underlying entity type. */
    Default,

    /** Actively pathfind toward a target. Minimal adapter falls back to [Default]. */
    Pathfind,

    /** Hold position, never pathfind. Useful for boss arenas / training dummies. */
    Stationary,

    /** Move away from threats. Minimal adapter falls back to [Default]. */
    Flee,

    /**
     * Aggressively engage any player in range. Minimal adapter falls back to
     * [Default] (no raycast targeting); MythicMobs adapter honours through
     * its own targeting rules.
     */
    Aggressive,
}
