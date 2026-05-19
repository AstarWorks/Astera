package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.service.InMemoryRewardBridge
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.event.MissionCompleted
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RewardOnMissionCompletedListenerTest {

    private lateinit var bridge: InMemoryRewardBridge
    private lateinit var listener: RewardOnMissionCompletedListener
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        bridge = InMemoryRewardBridge()
        listener = RewardOnMissionCompletedListener(bridge)
    }

    @Test
    fun `uses norma reward when currencyId is a known Currency`() {
        val event = MissionCompleted(
            playerId = playerId,
            normaId = NormaId("kills-10"),
            reward = NormaReward(currencyId = "GOLD", currencyAmount = 200L, xp = 75L),
        )

        listener.handle(event)

        val bundle = bridge.peek(playerId).single()
        assertThat(bundle.currency).containsEntry(Currency.GOLD, 200L)
        assertThat(bundle.xp).isEqualTo(75L)
        assertThat(bundle.playerId).isEqualTo(playerId)
    }

    @Test
    fun `parses currencyId case-insensitively`() {
        val event = MissionCompleted(
            playerId = playerId,
            normaId = NormaId("k"),
            reward = NormaReward(currencyId = "star", currencyAmount = 5L, xp = 1L),
        )

        listener.handle(event)

        val bundle = bridge.peek(playerId).single()
        assertThat(bundle.currency).containsEntry(Currency.STAR, 5L)
    }

    @Test
    fun `falls back to PoC defaults when currencyId is null`() {
        val event = MissionCompleted(
            playerId = playerId,
            normaId = NormaId("k"),
            reward = NormaReward(currencyId = null, currencyAmount = 0L, xp = 0L),
        )

        listener.handle(event)

        val bundle = bridge.peek(playerId).single()
        assertThat(bundle.currency).containsEntry(Currency.STAR, RewardOnMissionCompletedListener.DEFAULT_STAR)
        assertThat(bundle.xp).isEqualTo(RewardOnMissionCompletedListener.DEFAULT_XP)
    }

    @Test
    fun `falls back to PoC defaults when currencyId unknown`() {
        val event = MissionCompleted(
            playerId = playerId,
            normaId = NormaId("k"),
            reward = NormaReward(currencyId = "PLATINUM", currencyAmount = 99L, xp = 99L),
        )

        listener.handle(event)

        val bundle = bridge.peek(playerId).single()
        assertThat(bundle.currency).containsEntry(Currency.STAR, RewardOnMissionCompletedListener.DEFAULT_STAR)
        // xp > 0 in event is honored even if currencyId is unknown.
        assertThat(bundle.xp).isEqualTo(99L)
    }
}
