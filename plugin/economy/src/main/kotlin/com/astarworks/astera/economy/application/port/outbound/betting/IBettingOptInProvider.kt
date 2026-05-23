package com.astarworks.astera.economy.application.port.outbound.betting

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: whether [playerId] has opted-in to betting (Wave 5bet /
 * ADR-0103 §"Decision" → `enabledForPlayer: opt-in 必須`).
 *
 * Phase 1: the default in-memory impl is [InMemoryBettingOptInProvider]
 * with a player-scoped opt-in toggle. Wave 5t Admin/Ops moved player
 * preferences into `PlayerSettings`; the Postgres overlay (added in this
 * wave's persistence module) reads from there.
 *
 * Default semantics: **disabled** for every player. A player must opt-in
 * via `/astera-bet opt-in` (Phase 2 surface) before they can challenge
 * or accept a challenge.
 */
public interface IBettingOptInProvider {

    public fun isOptedIn(playerId: PlayerId): Boolean

    public fun setOptedIn(playerId: PlayerId, optedIn: Boolean)
}
