package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.stage.StageId
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Per-match snapshot: save on entry (MatchStarted), restore on exit (MatchEnded). Pure, no Paper. */
class MatchInventorySnapshotListenerTest {

    /** Live inventories keyed by player; absent key = offline. */
    private class FakeInventories : IPlayerInventoryGateway {
        val live = mutableMapOf<PlayerId, PlayerInventorySnapshot>()
        override fun capture(playerId: PlayerId): PlayerInventorySnapshot? = live[playerId]
        override fun apply(snapshot: PlayerInventorySnapshot): Boolean {
            if (snapshot.playerId !in live) return false
            live[snapshot.playerId] = snapshot
            return true
        }
    }

    private class FakeMatches(private val match: Match?) : IMatchRepository {
        override fun findActiveMatches(): Collection<Match> = listOfNotNull(match)
        override fun findMatchOf(playerId: PlayerId): Match? = match
        override fun findInPhase(phase: MatchPhase): Collection<Match> = emptyList()
        override fun findById(id: MatchId): Match? = match?.takeIf { it.id == id }
        override fun all(): Collection<Match> = listOfNotNull(match)
        override fun save(entity: Match) = Unit
        override fun delete(id: MatchId) = Unit
    }

    private val matchId = MatchId("m1")
    private val red = PlayerId(UUID.randomUUID())
    private val blue = PlayerId(UUID.randomUUID())
    private val inventories = FakeInventories()
    private val snapshots = InMemoryPlayerInventorySnapshotRepository()

    private fun inventoryOf(playerId: PlayerId, item: String): PlayerInventorySnapshot =
        PlayerInventorySnapshot.empty(playerId).copy(hotbar = List(9) { i ->
            if (i == 0) InventoryItemSlot.ContentItem(item, 1) else null
        })

    private fun listener(): MatchInventorySnapshotListener {
        val team = Team(TeamId("t"), MessageKey("t"), MessageKey("t.c"), setOf(red, blue))
        val match = Match(matchId, MatchPhase.ACTIVE, listOf(team), Score(mapOf(team.id to 0)), StageId("arena"), 0L)
        return MatchInventorySnapshotListener(
            gateway = { inventories },
            saveSnapshot = SaveInventorySnapshotUseCase(snapshots),
            matches = { FakeMatches(match) },
        )
    }

    private val started = MatchStarted(matchId, StageId("arena"), atTick = 0L)
    private val ended = MatchEnded(matchId, winningTeam = null, atTick = 10L, reason = EndReason.TimerExpired)

    @Test
    fun `entry saves the pre-match inventory and exit restores it`() {
        inventories.live[red] = inventoryOf(red, "lobby-item")
        inventories.live[blue] = inventoryOf(blue, "lobby-item")
        val l = listener()

        l.handleStarted(started)
        assertThat(snapshots.findLatest(red)!!.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("lobby-item", 1))
        assertThat(l.isHeld(red)).isTrue()

        // the match hands out a kit
        inventories.live[red] = inventoryOf(red, "match-kit")
        inventories.live[blue] = inventoryOf(blue, "match-kit")

        l.handleEnded(ended)

        assertThat(inventories.live[red]!!.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("lobby-item", 1))
        assertThat(inventories.live[blue]!!.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("lobby-item", 1))
        assertThat(snapshots.findLatest(red)!!.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("lobby-item", 1))
        assertThat(l.isHeld(red)).isFalse()
    }

    @Test
    fun `a player who left mid-match keeps the persisted entry snapshot`() {
        inventories.live[red] = inventoryOf(red, "lobby-item")
        inventories.live[blue] = inventoryOf(blue, "lobby-item")
        val l = listener()
        l.handleStarted(started)

        inventories.live.remove(blue) // disconnects during the match
        inventories.live[red] = inventoryOf(red, "match-kit")
        l.handleEnded(ended)

        assertThat(inventories.live[red]!!.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("lobby-item", 1))
        assertThat(snapshots.findLatest(blue)!!.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("lobby-item", 1))
    }

    @Test
    fun `a player entering after start is snapshotted once and restored at end`() {
        val team = Team(TeamId("t"), MessageKey("t"), MessageKey("t.c"), emptySet())
        val match = Match(matchId, MatchPhase.WAITING, listOf(team), Score(mapOf(team.id to 0)), StageId("arena"), 0L)
        val l = MatchInventorySnapshotListener({ inventories }, SaveInventorySnapshotUseCase(snapshots)) { FakeMatches(match) }
        inventories.live[red] = inventoryOf(red, "lobby-item")

        l.handleStarted(started) // empty teams capture nobody
        assertThat(l.isHeld(red)).isFalse()

        l.handleEntered(PlayerEnteredMatch(matchId, red))
        inventories.live[red] = inventoryOf(red, "match-kit")
        l.handleEntered(PlayerEnteredMatch(matchId, red)) // duplicate must not overwrite the entry snapshot
        assertThat(l.isHeld(red)).isTrue()

        l.handleEnded(ended)

        assertThat(inventories.live[red]!!.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("lobby-item", 1))
        assertThat(l.isHeld(red)).isFalse()
    }

    @Test
    fun `end without a recorded entry is a no-op`() {
        inventories.live[red] = inventoryOf(red, "kept")
        listener().handleEnded(ended)
        assertThat(inventories.live[red]!!.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("kept", 1))
        assertThat(snapshots.findLatest(red)).isNull()
    }

    @Test
    fun `without a gateway nothing is captured and nothing is held`() {
        val team = Team(TeamId("t"), MessageKey("t"), MessageKey("t.c"), setOf(red))
        val match = Match(matchId, MatchPhase.ACTIVE, listOf(team), Score(mapOf(team.id to 0)), StageId("arena"), 0L)
        val l = MatchInventorySnapshotListener({ null }, SaveInventorySnapshotUseCase(snapshots)) { FakeMatches(match) }

        l.handleStarted(started)

        assertThat(l.isHeld(red)).isFalse()
        assertThat(snapshots.findLatest(red)).isNull()
    }
}
