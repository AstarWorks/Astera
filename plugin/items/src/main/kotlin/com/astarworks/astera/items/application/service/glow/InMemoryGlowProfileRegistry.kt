package com.astarworks.astera.items.application.service.glow

import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.items.domain.glow.GlowProfile

/**
 * In-memory registry of per-rarity glow profiles (Wave 5flex / ADR-0102
 * surface #1).
 *
 * Seeded with [GlowProfile.DEFAULTS] at construction; YAML overrides
 * land via [registerAll] when `AsteraItemsPlugin` boots and the
 * `content/flex/glow-profile.yaml` loader emits the parsed list.
 *
 * Reads are O(1). Writes replace the entry for the affected rarity tier
 * only — other tiers keep their existing values. This lets ops hot-tune
 * a single tier without rewriting the whole table.
 */
public class InMemoryGlowProfileRegistry {

    // Copy-on-write: readers see a whole table, a reload swaps it in one write.
    @Volatile
    private var profiles: Map<Rarity, GlowProfile> = GlowProfile.DEFAULTS.toMap()

    /** Read the active profile for [rarity]. Always present (defaults fill). */
    public fun resolve(rarity: Rarity): GlowProfile =
        profiles[rarity]
            ?: GlowProfile.DEFAULTS[rarity]
            ?: error("missing glow default for rarity=$rarity")

    /** Replace the entry for [profile.rarity]. */
    public fun register(profile: GlowProfile) {
        profiles = profiles + (profile.rarity to profile)
    }

    /**
     * Bulk-replace from a YAML load. Profiles not present in [overrides]
     * fall back to the existing entry (which itself falls back to
     * [GlowProfile.DEFAULTS]). Re-callable for hot reload.
     */
    public fun registerAll(overrides: List<GlowProfile>) {
        profiles = profiles + overrides.associateBy { it.rarity }
    }

    /** Reload: [GlowProfile.DEFAULTS] plus [overrides], so an override removed from the file reverts to its default. */
    public fun replaceAll(overrides: List<GlowProfile>) {
        profiles = GlowProfile.DEFAULTS + overrides.associateBy { it.rarity }
    }

    /** Snapshot of every registered profile, ordered by enum declaration. */
    public fun snapshot(): List<GlowProfile> =
        Rarity.entries.map { resolve(it) }
}
