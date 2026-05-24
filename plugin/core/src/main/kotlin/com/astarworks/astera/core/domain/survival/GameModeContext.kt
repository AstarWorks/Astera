package com.astarworks.astera.core.domain.survival

/**
 * What 4-tier world bucket a player is currently in (Wave 5pve-survival /
 * ADR-0107).
 *
 * Used by [IInventoryShareGuard] to decide whether an item carried in
 * inventory can cross from one world tier to another. The 4 tiers mirror
 * the deployment topology user-confirmed 2026-05-20:
 *
 *  - Lobby (matchmaking entry)
 *  - PvP match world (per-match ephemeral)
 *  - PvE open world (Wave 5pve persistent)
 *  - Skyblock survival island (this wave's instance world)
 *
 * # Why sealed (not enum)
 *
 * Hybrid contexts may carry payload — e.g. a Phase 4 "tournament arena"
 * could be `PvpMatch(eventId = ...)`. The sealed hierarchy stays open
 * to additive variants without breaking the cross-mode rule matrix.
 */
public sealed interface GameModeContext {

    /** Matchmaking lobby / non-combat hub. */
    public data object Lobby : GameModeContext

    /** Per-match ephemeral PvP world. */
    public data object PvpMatch : GameModeContext

    /** Wave 5pve open PvE world (mob hunting, persistent loot). */
    public data object PveWorld : GameModeContext

    /** Wave 5pve-survival party-shared instance (this wave). */
    public data object SurvivalIsland : GameModeContext
}
