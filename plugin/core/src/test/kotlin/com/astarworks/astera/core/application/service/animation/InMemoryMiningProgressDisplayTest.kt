package com.astarworks.astera.core.application.service.animation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.mining.MiningProgressContext
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.testfixtures.FakeHudGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryMiningProgressDisplayTest {

    private val hud = FakeHudGateway()
    private val display = InMemoryMiningProgressDisplay(hud)
    private val player = PlayerId(UUID.randomUUID())

    private fun context(currentHp: Int, maxHp: Int = 100, etaTicks: Long = 0L): MiningProgressContext =
        MiningProgressContext(
            playerId = player,
            blockPosition = Vec3(0.0, 64.0, 0.0),
            currentHp = currentHp,
            maxHp = maxHp,
            toolType = ToolType.WOODEN_PICKAXE,
            etaTicks = etaTicks,
        )

    @Test
    fun `first update issues showFor on the HUD gateway`() {
        val result = display.update(player, context(currentHp = 80))
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val shownOps = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shownOps).hasSize(1)
        assertThat(shownOps.first().layout).isInstanceOf(HudLayout.ActionBar::class.java)
    }

    @Test
    fun `subsequent updates issue updateFor on the same handle`() {
        display.update(player, context(currentHp = 80))
        display.update(player, context(currentHp = 60))
        display.update(player, context(currentHp = 40))
        val shownOps = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        val updatedOps = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Updated>()
        assertThat(shownOps).hasSize(1)
        assertThat(updatedOps).hasSize(2)
        assertThat(updatedOps.first().handle).isEqualTo(shownOps.first().handle)
    }

    @Test
    fun `clear removes tracked handle and clears HUD`() {
        display.update(player, context(currentHp = 50))
        display.clear(player)
        val clearedOps = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Cleared>()
        assertThat(clearedOps).hasSize(1)
        // After clear, the next update should issue a fresh showFor
        display.update(player, context(currentHp = 30))
        val shownOps = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shownOps).hasSize(2)
    }

    @Test
    fun `MiningProgressContext rejects invalid invariants`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            MiningProgressContext(player, Vec3.ZERO, -1, 100, ToolType.WOODEN_PICKAXE, 0L)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            MiningProgressContext(player, Vec3.ZERO, 50, 0, ToolType.WOODEN_PICKAXE, 0L)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            MiningProgressContext(player, Vec3.ZERO, 50, 100, ToolType.WOODEN_PICKAXE, -1L)
        }
    }

    @Test
    fun `hpFraction reflects intact-to-broken sweep`() {
        assertThat(context(100).hpFraction).isEqualTo(1.0)
        assertThat(context(0).hpFraction).isEqualTo(0.0)
        assertThat(context(50).hpFraction).isEqualTo(0.5)
    }
}
