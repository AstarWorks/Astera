package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** F3-12: queue join -> entry snapshots the inventory -> match kit replaces it -> match end restores it. */
class MatchEntryInventoryScenarioTest {

    @Test
    fun `queue entry snapshots the inventory and match end restores it`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val sim = GameSim.boot("match-entry-inventory", snapshots = repo)
        try {
            val player = sim.spawnPlayer("p1")
            sim.step(5)
            val inventory = checkNotNull(sim.inventory)
            inventory.put(player.id, "sword")

            val rival = sim.spawnPlayer("p2")
            sim.services.getOrThrow<IMatchmaker>().enqueue(player.id, MatchmakingMode.Norma)
            sim.services.getOrThrow<IMatchmaker>().enqueue(rival.id, MatchmakingMode.Norma)
            assertThat(sim.stepUntil(600) { sim.eventsOf<MatchStarted>().isNotEmpty() }).isTrue()
            assertThat(sim.eventsOf<PlayerEnteredMatch>().map { it.playerId }).containsExactlyInAnyOrder(player.id, rival.id)
            assertThat(repo.findLatest(player.id)!!.hotbar[0]).isNotNull()

            inventory.put(player.id, "match-kit") // the match replaces the inventory
            assertThat(inventory.items(player.id)).containsExactly("match-kit")

            val matchId = sim.services.getOrThrow<IMatchRepository>().findActiveMatches().single().id
            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            sim.step(10)

            assertThat(inventory.items(player.id)).containsExactly("sword")
        } finally {
            sim.shutdown()
        }
    }

    @Test
    fun `an unarmed player is lent the starter weapons on entry, an armed one is left alone`() {
        val sim = GameSim.boot("match-entry-loaner", snapshots = InMemoryPlayerInventorySnapshotRepository())
        try {
            val armed = sim.spawnPlayer("armed")
            val unarmed = sim.spawnPlayer("unarmed")
            sim.step(5)
            val armedItems = checkNotNull(sim.server.view(armed.id)).givenItems
            val unarmedItems = checkNotNull(sim.server.view(unarmed.id)).givenItems
            assertThat(armedItems).containsExactly("pistol", "knight-sword") // starter grant
            unarmedItems.clear() // sold everything
            unarmedItems += "gui-pane-dark" // a non-weapon item does not count as a kit

            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            matchmaker.enqueue(armed.id, MatchmakingMode.Norma)
            matchmaker.enqueue(unarmed.id, MatchmakingMode.Norma)
            assertThat(sim.stepUntil(600) { sim.eventsOf<PlayerEnteredMatch>().size == 2 }).isTrue()

            assertThat(armedItems).containsExactly("pistol", "knight-sword")
            assertThat(unarmedItems).containsExactly("gui-pane-dark", "pistol", "knight-sword")
            assertThat(checkNotNull(sim.server.view(unarmed.id)).messages).anyMatch { "on loan" in it }
        } finally {
            sim.shutdown()
        }
    }
}
