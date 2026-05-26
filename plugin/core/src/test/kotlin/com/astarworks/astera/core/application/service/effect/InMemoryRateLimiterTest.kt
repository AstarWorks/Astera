package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.domain.effect.ratelimit.RateLimitedAction
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryRateLimiterTest {

    private val action = RateLimitedAction("skill-cast")
    private val player = PlayerId(UUID.randomUUID())

    @Test
    fun `accepts up to maxPerWindow then rejects`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val sut = InMemoryRateLimiter(scheduler)

        repeat(3) {
            assertThat(sut.tryConsume(player, action, maxPerWindow = 3, windowTicks = 20L)).isTrue()
        }
        assertThat(sut.tryConsume(player, action, 3, 20L)).isFalse()
    }

    @Test
    fun `slots expire after window`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val sut = InMemoryRateLimiter(scheduler)

        repeat(3) {
            assertThat(sut.tryConsume(player, action, 3, 20L)).isTrue()
        }
        scheduler.advanceTicks(25)
        assertThat(sut.tryConsume(player, action, 3, 20L)).isTrue()
    }

    @Test
    fun `reset clears state for one player only`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val sut = InMemoryRateLimiter(scheduler)
        val other = PlayerId(UUID.randomUUID())

        repeat(3) { sut.tryConsume(player, action, 3, 20L) }
        repeat(3) { sut.tryConsume(other, action, 3, 20L) }
        sut.reset(player)
        assertThat(sut.tryConsume(player, action, 3, 20L)).isTrue()
        assertThat(sut.tryConsume(other, action, 3, 20L)).isFalse()
    }

    @Test
    fun `independent action ids share no budget`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val sut = InMemoryRateLimiter(scheduler)

        repeat(3) { sut.tryConsume(player, RateLimitedAction("skill-cast"), 3, 20L) }
        assertThat(sut.tryConsume(player, RateLimitedAction("chat-msg"), 3, 20L)).isTrue()
    }
}
