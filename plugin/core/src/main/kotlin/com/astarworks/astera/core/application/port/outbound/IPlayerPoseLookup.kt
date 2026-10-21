package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose

/**
 * Outbound port: live "where is this player and where are they looking" read.
 *
 * ADR-0174. Distinct from [IPlayerLocationLookup], which only knows the
 * last-known position from `PlayerMoved` events (no facing, can be stale).
 * The Paper impl reads the Bukkit `Player` directly (source of truth); the
 * simulator / test fake serves scripted poses.
 *
 * Returns `null` when the player is offline / unknown.
 */
public interface IPlayerPoseLookup {
    public fun poseOf(playerId: PlayerId): PlayerPose?
}
