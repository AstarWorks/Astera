package com.astarworks.astera.core.domain.combat

import com.astarworks.astera.core.domain.match.MatchId

/**
 * Where a combat event happened (ADR-0237). Stamped on `DamageDealt` / `PlayerKilled` / `MobKilled`
 * by the code that creates the event, so every consumer decides from the event itself instead of
 * guessing from the target.
 *
 * Only [Match] is "real" play: progression (XP, missions, quests, achievements, stats, kill credit,
 * assists, the kill feed) counts it and nothing else. [Training], [Lobby] and [World] only drive the
 * local feedback (damage numbers, DPS, hit sounds).
 */
public sealed interface CombatContext {

    /**
     * Both sides are participants of a running match. [matchId] is `null` only when the match lookup
     * in use cannot name the match (test doubles); production lookups always fill it.
     */
    public data class Match(val matchId: MatchId? = null) : CombatContext

    /** The target is a lobby training dummy (ADR-0200). Dummies never die and never pay out. */
    public data object Training : CombatContext

    /** Players fighting outside a match (lobby, queue) - no PvP credit. */
    public data object Lobby : CombatContext

    /** Mobs and anything else outside a match (lobby mobs, wild PvE) - no credit for now. */
    public data object World : CombatContext

    /** True when progression / economy / stat / kill-credit consumers should count this event. */
    public val countsForProgression: Boolean
        get() = this is Match
}
