package com.astarworks.astera.items.domain.glow

import com.astarworks.astera.core.domain.item.Rarity

/**
 * Per-rarity visual treatment profile (Wave 5flex / ADR-0102 surface #1 —
 * Item rarity glow effect).
 *
 * Pure domain value: declares the *intent* of the visual treatment for a
 * given [Rarity] tier. The actual rendering is performed by an
 * [com.astarworks.astera.items.application.port.outbound.IItemRenderer]
 * implementation (in-memory recorder for tests, Paper-side particle
 * spawner for production).
 *
 * # Why per-rarity, not per-item
 *
 * ADR-0102 §1 calls out "rarity / refinement tier に応じた particle
 * frequency". The intent is a layered system that everyone recognises at
 * a glance: a Mythic / Recomb item visibly stands out across the lobby,
 * a Common item is silent. Authoring per-item glows would let designers
 * paint over rarity, which defeats the recognition signal — so the
 * profile is keyed strictly by tier.
 *
 * # Tunability (ADR-0102 §Negative — performance mitigation)
 *
 * - [particleTickPeriod] controls render frequency. Common = 0 (never),
 *   Legendary = every 10 ticks (twice per second). The Paper adapter
 *   schedules per the period so we never spawn more particles than
 *   declared. Tunable via `content/flex/glow-profile.yaml`.
 * - [particleCount] controls how many particles are emitted per tick.
 * - [glintOverride] forces the vanilla item-glint flag regardless of
 *   enchantments. Mythic items glow in inventory even before being
 *   enchanted (HSB Recombobulator visibility).
 * - [visibleFromBlocks] is the broadcast radius for *other* players.
 *   Common = 0 (only the holder sees), Legendary = 16 (visible across
 *   the lobby). Wave _ops-4 Prometheus measures per-tick particle render
 *   time so ops can re-tune.
 *
 * Phase 1 PoC ships sensible defaults — see [DEFAULTS] for the full
 * matrix. Designers override per server via the YAML registry.
 */
public data class GlowProfile(
    public val rarity: Rarity,
    public val particleTickPeriod: Int,
    public val particleCount: Int,
    public val glintOverride: Boolean,
    public val visibleFromBlocks: Int,
) {
    init {
        require(particleTickPeriod >= 0) {
            "GlowProfile.particleTickPeriod must be >= 0 (0 = never render), got $particleTickPeriod"
        }
        require(particleCount >= 0) {
            "GlowProfile.particleCount must be >= 0, got $particleCount"
        }
        require(visibleFromBlocks >= 0) {
            "GlowProfile.visibleFromBlocks must be >= 0, got $visibleFromBlocks"
        }
    }

    /** `true` iff this profile would result in any visible effect. */
    public val active: Boolean
        get() = particleTickPeriod > 0 && particleCount > 0

    public companion object {
        /**
         * Default per-rarity glow ladder. Common = silent; intensity grows
         * with rarity, peaking at Legendary visible-across-lobby treatment.
         * Wave 5flex ships these as the InMemory fallback; YAML overrides
         * land at priority 0 inside the registry.
         */
        public val DEFAULTS: Map<Rarity, GlowProfile> = mapOf(
            Rarity.COMMON to GlowProfile(
                rarity = Rarity.COMMON,
                particleTickPeriod = 0,
                particleCount = 0,
                glintOverride = false,
                visibleFromBlocks = 0,
            ),
            Rarity.UNCOMMON to GlowProfile(
                rarity = Rarity.UNCOMMON,
                particleTickPeriod = 40,
                particleCount = 1,
                glintOverride = false,
                visibleFromBlocks = 4,
            ),
            Rarity.RARE to GlowProfile(
                rarity = Rarity.RARE,
                particleTickPeriod = 30,
                particleCount = 2,
                glintOverride = false,
                visibleFromBlocks = 6,
            ),
            Rarity.EPIC to GlowProfile(
                rarity = Rarity.EPIC,
                particleTickPeriod = 20,
                particleCount = 3,
                glintOverride = true,
                visibleFromBlocks = 10,
            ),
            Rarity.LEGENDARY to GlowProfile(
                rarity = Rarity.LEGENDARY,
                particleTickPeriod = 10,
                particleCount = 4,
                glintOverride = true,
                visibleFromBlocks = 16,
            ),
        )
    }
}
