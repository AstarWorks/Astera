package com.astarworks.astera.core.domain.reward

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.UUID

@JvmInline
public value class RewardBundleId(public val value: UUID) {
    public companion object {
        public fun random(): RewardBundleId = RewardBundleId(UUID.randomUUID())
    }
}

/**
 * Cross-server reward payload (Wave 5e, RTM issue #6).
 *
 * `MatchEnded` / `MissionCompleted` listeners enqueue bundles on the
 * game-server side; the lobby-server side consumes them when the player
 * reconnects. In Wave 5e the bridge is in-memory only (single JVM);
 * Wave 6 swaps to Redis pub/sub.
 *
 * Worker ζ fills the listeners + InMemoryRewardBridge. Phase A defines
 * the value shape with items, currency, and XP slots — sufficient for the
 * Phase 1 PoC.
 */
public data class RewardBundle(
    val id: RewardBundleId,
    val playerId: PlayerId,
    val items: List<RewardItem>,
    val currency: Map<Currency, Long>,
    val xp: Long,
    val createdAt: Instant,
) {
    public data class RewardItem(val itemId: String, val count: Int) {
        init { require(count >= 1) { "RewardItem.count must be >= 1: $count" } }
    }

    init {
        require(xp >= 0) { "RewardBundle.xp must be >= 0: $xp" }
        currency.forEach { (c, amount) ->
            require(amount >= 0) { "RewardBundle currency $c must be >= 0: $amount" }
        }
    }
}
