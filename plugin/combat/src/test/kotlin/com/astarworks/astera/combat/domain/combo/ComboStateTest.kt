package com.astarworks.astera.combat.domain.combo

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ComboStateTest {

    private val player = PlayerId(UUID.randomUUID())
    private val sword = ItemId("example-sword")

    @Test
    fun `single hit yields streak count 1`() {
        val state = ComboState(
            playerId = player,
            weaponId = sword,
            steps = listOf(ComboStep.Hit(player, 5.0, atTick = 10)),
            lastStepAtTick = 10,
        )
        assertThat(state.streakCount).isEqualTo(1)
        assertThat(state.timeoutTicks).isEqualTo(ComboState.DEFAULT_TIMEOUT_TICKS)
    }

    @Test
    fun `expires when nowTick is beyond lastStep plus timeout`() {
        val state = ComboState(
            playerId = player,
            weaponId = sword,
            steps = listOf(ComboStep.Hit(player, 5.0, atTick = 100)),
            lastStepAtTick = 100,
            timeoutTicks = 60,
        )
        assertThat(state.isExpiredAt(160)).isFalse() // edge: exactly at timeout, still alive
        assertThat(state.isExpiredAt(161)).isTrue()
    }

    @Test
    fun `empty steps list rejected at construction`() {
        assertThatThrownBy {
            ComboState(player, sword, emptyList(), lastStepAtTick = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("steps must not be empty")
    }

    @Test
    fun `unsorted steps rejected`() {
        assertThatThrownBy {
            ComboState(
                player, sword,
                steps = listOf(
                    ComboStep.Hit(player, 5.0, atTick = 50),
                    ComboStep.Hit(player, 5.0, atTick = 10),
                ),
                lastStepAtTick = 10,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ascending atTick order")
    }

    @Test
    fun `lastStepAtTick must equal last step tick`() {
        assertThatThrownBy {
            ComboState(
                player, sword,
                steps = listOf(ComboStep.Hit(player, 5.0, atTick = 10)),
                lastStepAtTick = 20,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must equal last step's atTick")
    }

    @Test
    fun `timeoutTicks must be positive`() {
        assertThatThrownBy {
            ComboState(
                player, sword,
                steps = listOf(ComboStep.Hit(player, 5.0, atTick = 0)),
                lastStepAtTick = 0,
                timeoutTicks = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("timeoutTicks must be > 0")
    }
}
