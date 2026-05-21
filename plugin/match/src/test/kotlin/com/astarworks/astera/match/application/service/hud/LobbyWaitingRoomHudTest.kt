package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchQueueJoined
import com.astarworks.astera.core.domain.event.MatchQueueLeft
import com.astarworks.astera.core.domain.event.lifecycle.ServerShutdownStarted
import com.astarworks.astera.core.domain.lifecycle.ShutdownReason
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.matchmaking.InMemoryMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.testfixtures.FakeBossBarManager
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeScoreboardManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class LobbyWaitingRoomHudTest {

    private fun newFixture(): Fixture {
        val bus = FakeDomainEventBus()
        val mm = InMemoryMatchmaker(eventBus = bus, scheduler = FakeScheduler())
        val bossBars = FakeBossBarManager()
        val scoreboards = FakeScoreboardManager()
        val server = FakeMcServer()
        val scheduler = FakeScheduler()
        val hud = LobbyWaitingRoomHud(
            matchmaker = mm,
            bossBars = bossBars,
            scoreboards = scoreboards,
            mcServer = server,
            eventBus = bus,
            scheduler = scheduler,
        )
        return Fixture(bus, mm, bossBars, scoreboards, server, hud)
    }

    private fun registerPlayer(server: FakeMcServer, id: PlayerId): FakeMcPlayer {
        val p = FakeMcPlayer(id = id, name = "p-${id.uuid.toString().take(6)}")
        server.register(p)
        return p
    }

    @Test
    fun `onEnter creates bossbar and scoreboard for the player`() {
        val f = newFixture()
        val pid = PlayerId(UUID.randomUUID())
        registerPlayer(f.server, pid)
        f.hud.onEnter(pid)
        assertThat(f.hud.trackedPlayers()).contains(pid)
        assertThat(f.bossBars.activeBars).hasSize(1)
        // Single sidebar shown to pid.
        assertThat(f.scoreboards.currentScoreboard(pid)).isNotNull
    }

    @Test
    fun `onLeave removes bossbar and scoreboard`() {
        val f = newFixture()
        val pid = PlayerId(UUID.randomUUID())
        registerPlayer(f.server, pid)
        f.hud.onEnter(pid)
        f.hud.onLeave(pid)
        assertThat(f.hud.trackedPlayers()).doesNotContain(pid)
        assertThat(f.scoreboards.currentScoreboard(pid)).isNull()
    }

    @Test
    fun `refresh sends action-bar with queue placeholders when player is queued`() {
        val f = newFixture()
        val pid = PlayerId(UUID.randomUUID())
        val mcPlayer = registerPlayer(f.server, pid)
        f.hud.onEnter(pid)
        f.matchmaker.enqueue(pid, MatchmakingMode.Norma)
        f.hud.refresh(pid)
        // Last action bar should include the queue-status key + placeholders.
        val last = mcPlayer.actionBars.last()
        assertThat(last.key.value).isEqualTo("astera.lobby.queue-status")
        assertThat(last.placeholders).containsKeys("mode", "position", "eta")
        assertThat(last.placeholders["mode"]).isEqualTo("Norma")
    }

    @Test
    fun `refresh sends not-queued action-bar when no queue entry`() {
        val f = newFixture()
        val pid = PlayerId(UUID.randomUUID())
        val mcPlayer = registerPlayer(f.server, pid)
        f.hud.onEnter(pid)
        f.hud.refresh(pid)
        val notQueued = mcPlayer.actionBars.last()
        assertThat(notQueued.key.value).isEqualTo("astera.lobby.not-queued")
    }

    @Test
    fun `subscribe wires MatchQueueJoined to refresh`() {
        val f = newFixture()
        val pid = PlayerId(UUID.randomUUID())
        val mcPlayer = registerPlayer(f.server, pid)
        f.hud.onEnter(pid)
        f.hud.subscribe()
        // enqueue publishes MatchQueueJoined; the subscription should refresh.
        f.matchmaker.enqueue(pid, MatchmakingMode.Norma)
        // Last action bar reflects the new queue state.
        val last = mcPlayer.actionBars.last()
        assertThat(last.key.value).isEqualTo("astera.lobby.queue-status")
    }

    @Test
    fun `MatchFormed clears HUD and pushes match-found action-bar`() {
        val f = newFixture()
        val a = PlayerId(UUID.randomUUID()); val b = PlayerId(UUID.randomUUID())
        val mcA = registerPlayer(f.server, a)
        val mcB = registerPlayer(f.server, b)
        f.hud.onEnter(a); f.hud.onEnter(b)
        f.hud.subscribe()
        f.matchmaker.enqueue(a, MatchmakingMode.Norma)
        f.matchmaker.enqueue(b, MatchmakingMode.Norma)
        f.matchmaker.tryFormMatch(MatchmakingMode.Norma)
        // Both players received the match-found action bar; HUD untracked.
        assertThat(mcA.actionBars.last().key.value).isEqualTo("astera.lobby.match-found")
        assertThat(mcB.actionBars.last().key.value).isEqualTo("astera.lobby.match-found")
        assertThat(f.hud.trackedPlayers()).doesNotContain(a, b)
    }

    @Test
    fun `ServerShutdownStarted clears tracked players`() {
        val f = newFixture()
        val a = PlayerId(UUID.randomUUID()); val b = PlayerId(UUID.randomUUID())
        registerPlayer(f.server, a); registerPlayer(f.server, b)
        f.hud.onEnter(a); f.hud.onEnter(b)
        f.hud.subscribe()
        f.bus.publish(ServerShutdownStarted(reason = ShutdownReason.Planned))
        assertThat(f.hud.trackedPlayers()).isEmpty()
    }

    @Test
    fun `unsubscribe stops reacting to events`() {
        val f = newFixture()
        val pid = PlayerId(UUID.randomUUID())
        val mcPlayer = registerPlayer(f.server, pid)
        f.hud.onEnter(pid)
        f.hud.subscribe()
        f.hud.unsubscribe()
        val countBefore = mcPlayer.actionBars.size
        // Publish a queue-joined event after unsubscribe — no fresh refresh.
        f.bus.publish(
            MatchQueueJoined(
                playerId = pid,
                modeKey = "Norma",
                partyId = null,
                enqueuedAt = java.time.Instant.now(),
            ),
        )
        // The subscription is gone, so the count doesn't grow.
        assertThat(mcPlayer.actionBars.size).isEqualTo(countBefore)
    }

    @Test
    fun `MatchQueueLeft outside match-formed reason untracks the player`() {
        val f = newFixture()
        val pid = PlayerId(UUID.randomUUID())
        registerPlayer(f.server, pid)
        f.hud.onEnter(pid)
        f.hud.subscribe()
        f.bus.publish(
            MatchQueueLeft(
                playerId = pid,
                modeKey = "Norma",
                reasonTag = "player-dequeued",
            ),
        )
        assertThat(f.hud.trackedPlayers()).doesNotContain(pid)
    }

    @Test
    fun `refresh is no-op for untracked player`() {
        val f = newFixture()
        val pid = PlayerId(UUID.randomUUID())
        val mcPlayer = registerPlayer(f.server, pid)
        f.hud.refresh(pid)
        assertThat(mcPlayer.actionBars).isEmpty()
    }

    private data class Fixture(
        val bus: FakeDomainEventBus,
        val matchmaker: InMemoryMatchmaker,
        val bossBars: FakeBossBarManager,
        val scoreboards: FakeScoreboardManager,
        val server: FakeMcServer,
        val hud: LobbyWaitingRoomHud,
    )
}
