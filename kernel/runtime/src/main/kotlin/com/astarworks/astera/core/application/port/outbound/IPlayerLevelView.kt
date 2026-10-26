package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Read-only view of a player's account level, published by `:plugin:stats`
 * (which owns the level service) so core GUIs such as the profile card can
 * show it without depending on the stats plugin.
 */
public interface IPlayerLevelView {
    public fun levelOf(playerId: PlayerId): Int
    public fun totalXpOf(playerId: PlayerId): Long
}
