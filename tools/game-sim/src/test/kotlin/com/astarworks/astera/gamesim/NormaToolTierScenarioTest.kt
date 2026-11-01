package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.domain.event.NormaLevelUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0319: the loaned field tools follow the Norma level (Lv1 stone, Lv2 iron, Lv4 diamond), upgraded in place on a
 * level-up and re-lent at the current tier on a rejoin. Played on the real `content/`.
 */
class NormaToolTierScenarioTest {

    @Test
    fun `loaned tools upgrade in place on the tier levels and a rejoin gets the current tier`() {
        // Snapshots are what makes a match entry lend anything (the loan runs right after the entry snapshot).
        val sim = GameSim.boot("norma-tool-tier", snapshots = InMemoryPlayerInventorySnapshotRepository())
        try {
            ScenarioScope(sim).run {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val norma = sim.services.getOrThrow<IMatchNormaStore>()
            val items = checkNotNull(sim.server.view(red.id)).givenItems
            val stone = ShippedStarterGrant.toolsAt(1)
            val iron = ShippedStarterGrant.toolsAt(2)
            val diamond = ShippedStarterGrant.toolsAt(4)
            assertThat(stone).allMatch { it.startsWith("stone-") }
            assertThat(iron).allMatch { it.startsWith("iron-") }
            assertThat(diamond).allMatch { it.startsWith("diamond-") }
            assertThat(ShippedStarterGrant.toolsAt(3)).isEqualTo(iron)

            fun levelTo(level: Int) {
                norma.put(matchId, red.id, norma.get(matchId, red.id).copy(level = level))
                sim.eventBus.publish(NormaLevelUp(matchId, red.id, level))
                advance(2)
            }
            fun tools() = items.filter { it in stone + iron + diamond }

            assertThat(tools()).`as`("Lv1 entry").containsExactlyElementsOf(stone)
            val slotsBefore = items.toList()

            levelTo(2)
            assertThat(tools()).`as`("Lv2").containsExactlyElementsOf(iron)
            assertThat(items.size).isEqualTo(slotsBefore.size)
            stone.forEachIndexed { i, s -> assertThat(items.indexOf(iron[i])).isEqualTo(slotsBefore.indexOf(s)) }

            val afterLv2 = items.toList()
            levelTo(3)
            assertThat(items).`as`("Lv3 changes nothing").containsExactlyElementsOf(afterLv2)

            levelTo(4)
            assertThat(tools()).`as`("Lv4").containsExactlyElementsOf(diamond)
            iron.forEachIndexed { i, s -> assertThat(items.indexOf(diamond[i])).isEqualTo(afterLv2.indexOf(s)) }

            // Rejoin at Lv4: the restored inventory has no loaned tools, the rearm lends the current tier.
            items.removeAll((stone + iron + diamond).toSet())
            sim.services.getOrThrow<MatchInventorySnapshotListener>().rearm(red.id)
            assertThat(tools()).`as`("rejoin at Lv4").containsExactlyElementsOf(diamond)
            }
        } finally {
            sim.shutdown()
        }
    }
}
