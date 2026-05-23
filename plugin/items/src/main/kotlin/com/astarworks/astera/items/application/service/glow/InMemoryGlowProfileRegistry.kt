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

    private val profiles: MutableMap<Rarity, GlowProfile> =
        GlowProfile.DEFAULTS.toMutableMap()

    /** Read the active profile for [rarity]. Always present (defaults fill). */
    public fun resolve(rarity: Rarity): GlowProfile =
        profiles[rarity]
            ?: GlowProfile.DEFAULTS[rarity]
            ?: error("missing glow default for rarity=$rarity")

    /** Replace the entry for [profile.rarity]. */
    public fun register(profile: GlowProfile) {
        profiles[profile.rarity] = profile
    }

    /**
     * Bulk-replace from a YAML load. Profiles not present in [overrides]
     * fall back to the existing entry (which itself falls back to
     * [GlowProfile.DEFAULTS]). Re-callable for hot reload.
     */
    public fun registerAll(overrides: List<GlowProfile>) {
        for (p in overrides) {
            profiles[p.rarity] = p
        }
    }

    /** Snapshot of every registered profile, ordered by enum declaration. */
    public fun snapshot(): List<GlowProfile> =
        Rarity.entries.map { resolve(it) }
}
