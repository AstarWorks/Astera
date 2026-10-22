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

            sim.services.getOrThrow<IMatchmaker>().enqueue(player.id, MatchmakingMode.Norma)
            assertThat(sim.stepUntil(600) { sim.eventsOf<MatchStarted>().isNotEmpty() }).isTrue()
            assertThat(sim.eventsOf<PlayerEnteredMatch>().map { it.playerId }).containsExactly(player.id)
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
}
