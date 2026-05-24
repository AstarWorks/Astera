package com.astarworks.astera.combat.domain.focus

import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FocusFireStateTest {

    private val victim = PlayerId(UUID.randomUUID())
    private val a = PlayerId(UUID.randomUUID())
    private val b = PlayerId(UUID.randomUUID())
    private val c = PlayerId(UUID.randomUUID())

    private fun contrib(p: PlayerId, dmg: Double = 1.0, hits: Int = 1, atTick: Long = 0): FocusFireContribution =
        FocusFireContribution(p, dmg, hits, atTick)

    @Test
    fun `isActive true when minAttackers contribute within window`() {
        val state = FocusFireState(
            victim = victim,
            contributions = listOf(
                contrib(a, atTick = 90),
                contrib(b, atTick = 95),
                contrib(c, atTick = 100),
            ),
            windowStartedAtTick = 90,
        )
        val cfg = FocusFireConfig(minAttackers = 3, windowTicks = 100, bonusMultiplier = 1.2)
        assertThat(state.isActive(cfg, nowTick = 110)).isTrue()
    }

    @Test
    fun `isActive false when contributors fall below threshold`() {
        val state = FocusFireState(
            victim = victim,
            contributions = listOf(contrib(a, atTick = 0), contrib(b, atTick = 5)),
            windowStartedAtTick = 0,
        )
        val cfg = FocusFireConfig(minAttackers = 3, windowTicks = 100, bonusMultiplier = 1.2)
        assertThat(state.isActive(cfg, nowTick = 10)).isFalse()
    }

    @Test
    fun `isActive false when all hits are outside the window`() {
        val state = FocusFireState(
            victim = victim,
            contributions = listOf(
                contrib(a, atTick = 0),
                contrib(b, atTick = 5),
                contrib(c, atTick = 10),
            ),
            windowStartedAtTick = 0,
        )
        val cfg = FocusFireConfig(minAttackers = 3, windowTicks = 50, bonusMultiplier = 1.2)
        assertThat(state.isActive(cfg, nowTick = 200)).isFalse()
    }

    @Test
    fun `duplicate attacker entries rejected`() {
        assertThatThrownBy {
            FocusFireState(
                victim = victim,
                contributions = listOf(contrib(a, atTick = 0), contrib(a, atTick = 5)),
                windowStartedAtTick = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("one entry per attacker")
    }

    @Test
    fun `victim cannot appear as attacker`() {
        assertThatThrownBy {
            FocusFireState(
                victim = victim,
                contributions = listOf(contrib(victim, atTick = 0)),
                windowStartedAtTick = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not include the victim")
    }

    @Test
    fun `totalDamage sums contributions`() {
        val state = FocusFireState(
            victim = victim,
            contributions = listOf(contrib(a, dmg = 10.0), contrib(b, dmg = 5.0, atTick = 1)),
            windowStartedAtTick = 0,
        )
        assertThat(state.totalDamage).isEqualTo(15.0)
    }

    @Test
    fun `FocusFireConfig caps bonusMultiplier`() {
        assertThat(runCatching {
            FocusFireConfig(bonusMultiplier = 10.0)
        }.isFailure).isTrue()
    }
}
