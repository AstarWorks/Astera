package com.astarworks.astera.combat.domain.resource

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

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
        val newPool = (result as Result.Success).value
        assertThat(newPool.current).isEqualTo(70L)
        assertThat(newPool.max).isEqualTo(100L)
        // Identity-bearing fields are preserved.
        assertThat(newPool.playerId).isEqualTo(playerId)
        assertThat(newPool.regenPerSec).isEqualTo(1.0)
    }

    @Test
    fun `regen at 10 per sec over 20 ticks adds 10 (capped at max)`() {
        val pool = ManaPool(
            playerId = playerId,
            current = 20L,
            max = 100L,
            regenPerSec = 10.0,
            lastRegenTick = 0L,
        )

        val regenerated = pool.regen(elapsedTicks = 20L)

        // 20 ticks = 1 second; gain = 10.0 * 1.0 = 10 → current 20 → 30
        assertThat(regenerated.current).isEqualTo(30L)
        assertThat(regenerated.max).isEqualTo(100L)
        assertThat(regenerated.lastRegenTick).isEqualTo(20L)
    }

    @Test
    fun `regen at max leaves pool unchanged`() {
        val pool = ManaPool.initial(playerId, max = 50L, regenPerSec = 5.0)
        assertThat(pool.current).isEqualTo(pool.max)

        val regenerated = pool.regen(elapsedTicks = 200L)

        assertThat(regenerated).isEqualTo(pool)
    }
}
