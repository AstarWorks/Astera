package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The sim honours `IItemDelivery.give(amount)` and `startMatch` stamps each joined entity's team. */
class SimGiveAmountAndTeamScenarioTest {
    @Test
    fun `give honours amount and startMatch sets the entity team`() {
        scenario("sim-give-amount-team") {
            val runner = player("Runner")
            val enemy = player("Enemy")
            assertThat(runner.team).isNull()

            startMatch("carry-tnt", mapOf(runner to "red", enemy to "blue"), stage = "siege-isles-tnt")
            assertThat(runner.team?.value).isEqualTo("red")
            assertThat(enemy.team?.value).isEqualTo("blue")

            val items = sim.services.getOrThrow<IItemRegistry>()
            val spec = checkNotNull(items.find(ItemId("ore-gold"))) { "ore-gold missing" }
            val view = checkNotNull(sim.server.view(runner.id))
            sim.services.getOrThrow<IItemDelivery>().give(view, spec, 5)
            assertThat(view.givenItems.count { it == "ore-gold" }).isEqualTo(5)
        }
    }
}
