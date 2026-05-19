package com.astarworks.astera.combat.domain.resource

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5e (ADR-0058 §5): [ManaPool] is now a facade over
 * [SimpleAttributeContainer]. Mutations write through the container in place,
 * so the same instance reflects new state on the next read.
 */
class ManaPoolTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `spend more than current returns Insufficient`() {
        val pool = ManaPool.initial(playerId, max = 50L, regenPerSec = 1.0)

        val result = pool.spend(80L)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(ManaError.Insufficient::class.java)
        val insufficient = error as ManaError.Insufficient
        assertThat(insufficient.have).isEqualTo(50L)
        assertThat(insufficient.tried).isEqualTo(80L)
    }

    @Test
    fun `spend sufficient returns Success with reduced current`() {
        val pool = ManaPool.initial(playerId, max = 100L, regenPerSec = 1.0)

        val result = pool.spend(30L)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val mutated = (result as Result.Success).value
        // Wave 5e: container is the source of truth; spend returns `this`.
        assertThat(mutated).isSameAs(pool)
        assertThat(pool.current).isEqualTo(70L)
        assertThat(pool.max).isEqualTo(100L)
        assertThat(pool.playerId).isEqualTo(playerId)
        assertThat(pool.regenPerSec).isEqualTo(1.0)
    }

    @Test
    fun `regen at 10 per sec over 20 ticks adds 10 (capped at max)`() {
        val container = SimpleAttributeContainer()
        val pool = ManaPool.initial(
            playerId = playerId,
            max = 100L,
            regenPerSec = 10.0,
            container = container,
            startTick = 0L,
        )
        // Pre-spend to drop current to 20 (initial = max = 100).
        pool.spend(80L)
        assertThat(pool.current).isEqualTo(20L)

        pool.regen(elapsedTicks = 20L)

        // 20 ticks = 1 second; gain = 10.0 * 1.0 = 10 → current 20 → 30
        assertThat(pool.current).isEqualTo(30L)
        assertThat(pool.max).isEqualTo(100L)
        assertThat(pool.lastRegenTick).isEqualTo(20L)
    }

    @Test
    fun `regen at max leaves pool unchanged`() {
        val pool = ManaPool.initial(playerId, max = 50L, regenPerSec = 5.0)
        assertThat(pool.current).isEqualTo(pool.max)

        pool.regen(elapsedTicks = 200L)

        // Current already at max, regen is a no-op.
        assertThat(pool.current).isEqualTo(50L)
        assertThat(pool.lastRegenTick).isEqualTo(0L)
    }
}
