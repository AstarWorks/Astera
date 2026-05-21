package com.astarworks.astera.core.application.service.afk

import com.astarworks.astera.core.domain.afk.ActivityKind
import com.astarworks.astera.core.domain.afk.AfkStatus
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryAfkDetectorTest {

    private val player = PlayerId(UUID.randomUUID())

    @Test
    fun `no recorded activity returns Active`() {
        val now = LongRef(0L)
        val detector = InMemoryAfkDetector(tickProvider = { now.value })
        assertThat(detector.status(player)).isEqualTo(AfkStatus.Active)
    }

    @Test
    fun `activity within warning window stays Active`() {
        val now = LongRef(0L)
        val detector = InMemoryAfkDetector(tickProvider = { now.value })
        detector.recordActivity(player, ActivityKind.Movement, atTick = 0)
        now.value = 100 // 5s
        assertThat(detector.status(player)).isEqualTo(AfkStatus.Active)
    }

    @Test
    fun `30s inactivity yields Warning`() {
        val now = LongRef(0L)
        val detector = InMemoryAfkDetector(tickProvider = { now.value })
        detector.recordActivity(player, ActivityKind.Movement, atTick = 0)
        now.value = 600 + 1 // 30s + 1 tick
        val status = detector.status(player)
        assertThat(status).isInstanceOf(AfkStatus.Warning::class.java)
    }

    @Test
    fun `2 min inactivity yields Afk`() {
        val now = LongRef(0L)
        val detector = InMemoryAfkDetector(tickProvider = { now.value })
        detector.recordActivity(player, ActivityKind.Movement, atTick = 0)
        now.value = 2400 + 10 // 2 min + 10 ticks
        assertThat(detector.status(player)).isInstanceOf(AfkStatus.Afk::class.java)
    }

    @Test
    fun `Click and Chat both refresh the inactivity timer`() {
        val now = LongRef(0L)
        val detector = InMemoryAfkDetector(tickProvider = { now.value })
        detector.recordActivity(player, ActivityKind.Click, atTick = 0)
        now.value = 800 // would be Warning
        detector.recordActivity(player, ActivityKind.Chat, atTick = 800)
        assertThat(detector.status(player)).isEqualTo(AfkStatus.Active)
    }

    @Test
    fun `activeAfkers excludes Active players`() {
        val now = LongRef(0L)
        val detector = InMemoryAfkDetector(tickProvider = { now.value })
        val other = PlayerId(UUID.randomUUID())
        detector.recordActivity(player, ActivityKind.Movement, atTick = 0)
        detector.recordActivity(other, ActivityKind.Movement, atTick = 0)
        now.value = 700 // both Warning
        assertThat(detector.activeAfkers().map { it.playerId }).containsExactlyInAnyOrder(player, other)
    }

    @Test
    fun `reset removes tracking`() {
        val now = LongRef(0L)
        val detector = InMemoryAfkDetector(tickProvider = { now.value })
        detector.recordActivity(player, ActivityKind.SkillCast, atTick = 0)
        now.value = 3000
        detector.reset(player)
        assertThat(detector.status(player)).isEqualTo(AfkStatus.Active)
    }

    private class LongRef(var value: Long)
}
