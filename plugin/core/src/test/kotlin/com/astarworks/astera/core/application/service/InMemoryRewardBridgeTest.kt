package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.reward.RewardBundle
import com.astarworks.astera.core.domain.reward.RewardBundleId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryRewardBridgeTest {

    private val playerA = PlayerId(UUID.randomUUID())
    private val playerB = PlayerId(UUID.randomUUID())

    @Test
    fun `enqueue then consume returns the bundle`() {
        val bridge = InMemoryRewardBridge()
        val bundle = newBundle(playerA, xp = 50L)

        bridge.enqueue(bundle)
        val consumed = bridge.consumeForPlayer(playerA)

        assertThat(consumed).containsExactly(bundle)
    }

    @Test
    fun `consume drains the queue`() {
        val bridge = InMemoryRewardBridge()
        bridge.enqueue(newBundle(playerA, xp = 10L))

        bridge.consumeForPlayer(playerA)

        assertThat(bridge.peek(playerA)).isEmpty()
        assertThat(bridge.consumeForPlayer(playerA)).isEmpty()
    }

    @Test
    fun `enqueue preserves FIFO order for a player`() {
        val bridge = InMemoryRewardBridge()
        val first = newBundle(playerA, xp = 10L)
        val second = newBundle(playerA, xp = 20L)
        val third = newBundle(playerA, xp = 30L)

        bridge.enqueue(first)
        bridge.enqueue(second)
        bridge.enqueue(third)

        assertThat(bridge.consumeForPlayer(playerA)).containsExactly(first, second, third)
    }

    @Test
    fun `peek returns without consuming`() {
        val bridge = InMemoryRewardBridge()
        val bundle = newBundle(playerA, xp = 99L)
        bridge.enqueue(bundle)

        val peeked = bridge.peek(playerA)
        assertThat(peeked).containsExactly(bundle)
        // Still drainable.
        assertThat(bridge.consumeForPlayer(playerA)).containsExactly(bundle)
    }

    @Test
    fun `consume for one player does not affect another`() {
        val bridge = InMemoryRewardBridge()
        bridge.enqueue(newBundle(playerA, xp = 1L))
        bridge.enqueue(newBundle(playerB, xp = 2L))

        val consumedA = bridge.consumeForPlayer(playerA)

        assertThat(consumedA).hasSize(1)
        assertThat(bridge.peek(playerB)).hasSize(1)
    }

    @Test
    fun `consume on empty queue returns empty list`() {
        val bridge = InMemoryRewardBridge()
        assertThat(bridge.consumeForPlayer(playerA)).isEmpty()
        assertThat(bridge.peek(playerA)).isEmpty()
    }

    private fun newBundle(playerId: PlayerId, xp: Long): RewardBundle = RewardBundle(
        id = RewardBundleId.random(),
        playerId = playerId,
        items = emptyList(),
        currency = mapOf(Currency.STAR to 10L),
        xp = xp,
        createdAt = Instant.now(),
    )
}
