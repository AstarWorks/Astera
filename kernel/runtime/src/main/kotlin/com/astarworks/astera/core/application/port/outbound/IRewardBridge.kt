package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.reward.RewardBundle

/**
 * Outbound port: cross-server reward queue (Wave 5e, RTM issue #6).
 *
 * Wave 5e provides an in-memory single-JVM impl. Wave 6 swaps to Redis
 * pub/sub for the real 2-server (game ↔ lobby) bridge. Producer side
 * (`MatchEnded` / `MissionCompleted` listeners) calls [enqueue]; consumer
 * side (lobby join restore flow) calls [consumeForPlayer].
 */
public interface IRewardBridge {

    /** Push a bundle into the queue for later consumption. */
    public fun enqueue(bundle: RewardBundle)

    /**
     * Drain and return all bundles queued for [playerId]. Atomic: callers
     * must apply the bundles successfully before the call returns, or
     * accept that retried calls will not re-see them.
     */
    public fun consumeForPlayer(playerId: PlayerId): List<RewardBundle>

    /** Peek without consuming — admin / debug. */
    public fun peek(playerId: PlayerId): List<RewardBundle>
}
