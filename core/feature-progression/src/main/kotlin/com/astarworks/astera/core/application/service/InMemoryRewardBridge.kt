package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.reward.RewardBundle

/**
 * In-memory single-JVM [IRewardBridge] (Wave 5e, RTM issue #6).
 *
 * Backed by a per-player [MutableList] in FIFO order. Wave 6 replaces this
 * with a Redis-backed impl for the real game ↔ lobby split; the contract
 * (enqueue / drain / peek) stays identical. Not thread-safe — Astera
 * dispatches on the Paper main thread, see [InMemoryDomainEventBus].
 */
public class InMemoryRewardBridge : IRewardBridge {

    private val queue: MutableMap<PlayerId, MutableList<RewardBundle>> = mutableMapOf()

    override suspend fun enqueue(bundle: RewardBundle) {
        queue.getOrPut(bundle.playerId) { mutableListOf() } += bundle
    }

    override suspend fun consumeForPlayer(playerId: PlayerId): List<RewardBundle> {
        val pending = queue.remove(playerId) ?: return emptyList()
        return pending.toList()
    }

    override suspend fun peek(playerId: PlayerId): List<RewardBundle> =
        queue[playerId]?.toList() ?: emptyList()
}
