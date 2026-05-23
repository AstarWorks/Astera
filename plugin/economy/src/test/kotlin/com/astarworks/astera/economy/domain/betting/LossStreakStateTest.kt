package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class LossStreakStateTest {

    private val player = PlayerId(UUID.randomUUID())
    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val cooldownMs = 10_000L
    private val limit = 3

    @Test
    fun `empty starts with zero losses`() {
        val state = LossStreakState.empty(player)
        assertThat(state.consecutiveLosses).isEqualTo(0)
        assertThat(state.cooldownEndsAt).isNull()
    }

    @Test
    fun `applyLoss increments`() {
        val s1 = LossStreakState.empty(player).applyLoss(now, limit, cooldownMs)
        val s2 = s1.applyLoss(now.plusSeconds(1), limit, cooldownMs)
        assertThat(s1.consecutiveLosses).isEqualTo(1)
        assertThat(s2.consecutiveLosses).isEqualTo(2)
        assertThat(s2.cooldownEndsAt).isNull()
    }

    @Test
    fun `reaching limit opens cooldown`() {
        var state = LossStreakState.empty(player)
        repeat(limit) { state = state.applyLoss(now, limit, cooldownMs) }
        assertThat(state.cooldownEndsAt).isEqualTo(now.plusMillis(cooldownMs))
        assertThat(state.isCoolingDown(now)).isTrue()
        assertThat(state.isCoolingDown(now.plusMillis(cooldownMs + 1))).isFalse()
    }

    @Test
    fun `reset clears losses but retains in-future cooldown`() {
        val state = LossStreakState.empty(player)
            .applyLoss(now, limit, cooldownMs)
            .applyLoss(now, limit, cooldownMs)
            .applyLoss(now, limit, cooldownMs)
        val reset = state.reset(now)
        assertThat(reset.consecutiveLosses).isEqualTo(0)
        assertThat(reset.cooldownEndsAt).isEqualTo(state.cooldownEndsAt)
    }

    @Test
    fun `reset drops expired cooldown`() {
        val state = LossStreakState.empty(player)
            .applyLoss(now, limit, cooldownMs)
            .applyLoss(now, limit, cooldownMs)
            .applyLoss(now, limit, cooldownMs)
        val reset = state.reset(now.plusMillis(cooldownMs + 1))
        assertThat(reset.consecutiveLosses).isEqualTo(0)
        assertThat(reset.cooldownEndsAt).isNull()
    }
}
