package com.astarworks.astera.core.domain.progression

/**
 * Enumeration of multi-skill progression axes (Wave 5multi-skill, ADR-0101).
 *
 * Each [SkillKind] is an independent progression axis with its own XP pool and
 * level. Hypixel SkyBlock ships 12 axes; OSRS ships 23 — Astera starts at 10
 * per the ADR's sweet-spot analysis (`docs/competitive-analysis/07-cross-cutting.md`
 * §2). The 10 axes were chosen so each maps to a distinct activity surface
 * (PvP / PvE / gathering / crafting / economy / exploration / events).
 *
 * # Wire format
 *
 * The lowercase [token] is the YAML / DB / event-payload key. Always use
 * [fromToken] to parse external input — direct [valueOf] is case-sensitive
 * and brittle to typos. Promote new axes by appending; **never reorder or
 * rename** existing entries (the persisted `skill_kind` column stores the
 * token verbatim per V30).
 *
 * # Mapping to event sources
 *
 * Per ADR-0101 §Decision:
 *  - [Combat]       — PvP `DamageDealt` + match-win `MatchEnded`
 *  - [BossSlayer]   — `MobKilled` with boss flag (Wave 12a; until then plain mob kills feed here)
 *  - [Mining]       — `BlockBroken` from pickaxe / `BlockBrokenBySkill` mining-skill cast
 *  - [Fishing]      — new event (Phase 6+, not yet wired)
 *  - [Farming]      — new event (Phase 6+, not yet wired)
 *  - [Refining]     — `ItemRefined` (Wave 12c, not yet wired)
 *  - [Trading]      — `CurrencyChanged(reason = MarketplaceSale|Purchase)` proxy (until Wave 12c)
 *  - [Exploration]  — `EncounterZone` entry (Wave 12b, not yet wired)
 *  - [Tournament]   — tournament match completion (Wave 7b, not yet wired)
 *  - [Crafting]     — new event (Phase 9+, not yet wired)
 *
 * Listeners that don't yet have a feeder event remain dormant — the axis is
 * defined now so the YAML / migration / repo are stable when those waves land.
 */
public enum class SkillKind(public val token: String) {
    Combat("combat"),
    BossSlayer("boss_slayer"),
    Mining("mining"),
    Fishing("fishing"),
    Farming("farming"),
    Refining("refining"),
    Trading("trading"),
    Exploration("exploration"),
    Tournament("tournament"),
    Crafting("crafting"),
    ;

    public companion object {

        /** Resolve a token (case-insensitive, trimmed) to a [SkillKind] or `null`. */
        public fun fromToken(raw: String): SkillKind? {
            val needle = raw.trim().lowercase()
            return entries.firstOrNull { it.token == needle }
        }

        /** Total number of progression axes (currently 10; ADR-0101 documents the bound). */
        public val AXIS_COUNT: Int get() = entries.size
    }
}
