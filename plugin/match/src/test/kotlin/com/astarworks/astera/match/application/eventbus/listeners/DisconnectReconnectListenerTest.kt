package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.usecase.match.ApplyForfeitUseCase
import com.astarworks.astera.match.application.usecase.match.RemoveFromMatchUseCase
import com.astarworks.astera.match.application.usecase.match.ResumeMatchUseCase
import com.astarworks.astera.match.domain.event.PlayerDisconnectedFromMatch
import com.astarworks.astera.match.domain.event.PlayerForfeited
import com.astarworks.astera.match.domain.event.PlayerReconnectedToMatch
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5h: end-to-end scenarios for
 * [DisconnectDuringMatchListener] + [ReconnectDuringMatchListener] +
 * [RemoveFromMatchUseCase] + [ResumeMatchUseCase] + [ApplyForfeitUseCase].
 *
 * Drives both listeners via [FakeDomainEventBus] so the wiring path mirrors
 * production (`AsteraMatchPlugin` will subscribe both classes).
 */
class DisconnectReconnectListenerTest {

    private lateinit var sessions: InMemoryPlayerSessionRepository
    private lateinit var matches: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var worldGateway: FakeWorldGateway

    private lateinit var removeFromMatch: RemoveFromMatchUseCase
    private lateinit var resumeMatch: ResumeMatchUseCase
    private lateinit var applyForfeit: ApplyForfeitUseCase

    private lateinit var disconnectListener: DisconnectDuringMatchListener
    private lateinit var reconnectListener: ReconnectDuringMatchListener

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
    private val red = TeamId("red")
    private val matchId = MatchId("match-disco")

    @BeforeEach
    fun setUp() {
        sessions = InMemoryPlayerSessionRepository()
        matches = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        scheduler = FakeScheduler(startTick = 1000L)
        worldGateway = FakeWorldGateway()

        removeFromMatch = RemoveFromMatchUseCase(
            sessions = sessions,
            matches = matches,
            worldGateway = worldGateway,
            eventBus = eventBus,
            scheduler = scheduler,
        )
        resumeMatch = ResumeMatchUseCase(
            sessions = sessions,
            matches = matches,
            worldGateway = worldGateway,
            eventBus = eventBus,
            scheduler = scheduler,
        )
        applyForfeit = ApplyForfeitUseCase(
            removeFromMatch = removeFromMatch,
            eventBus = eventBus,
            scheduler = scheduler,
        )

        disconnectListener = DisconnectDuringMatchListener(
            sessions = sessions,
            matches = matches,
            removeFromMatch = removeFromMatch,
            applyForfeit = applyForfeit,
            eventBus = eventBus,
            scheduler = scheduler,
        )
        reconnectListener = ReconnectDuringMatchListener(
            sessions = sessions,
            resumeMatch = resumeMatch,
            worldGateway = worldGateway,
        )

        eventBus.subscribe(PlayerQuitServer::class) { disconnectListener.handle(it) }
        eventBus.subscribe(PlayerJoinedServer::class) { reconnectListener.handle(it) }
    }

    private fun buildMatch(phase: MatchPhase): Match = Match(
        id = matchId,
        phase = phase,
        teams = listOf(
            Team(
                id = red,
                displayNameKey = MessageKey("astera.match.team.red"),
                colorKey = MessageKey("astera.match.team.red.color"),
                members = setOf(playerId),
            ),
        ),
        score = Score(mapOf(red to 0)),
        stageId = StageId("test-arena"),
        startedAtTick = 0L,
    )

    // ─── 1. COUNTDOWN — disconnect kicks immediately ─────────────────────

    @Test
    fun `PlayerQuit during COUNTDOWN removes player from match immediately`() {
        sessions.upsert(PlayerSession(playerId, SessionState.InMatch, currentMatch = matchId))
        matches.save(buildMatch(MatchPhase.COUNTDOWN))

        eventBus.publish(PlayerQuitServer(playerId))

        // Session reset to Lobby + currentMatch cleared.
        val after = sessions.find(playerId)
        assertThat(after).isNotNull
        assertThat(after!!.state).isEqualTo(SessionState.Lobby)
        assertThat(after.currentMatch).isNull()

        // Lobby spawn was called (via RemoveFromMatchUseCase).
        assertThat(worldGateway.spawnCalls)
            .anyMatch { it.playerId == playerId && it.target == "lobby" }

        // Terminal PlayerDisconnectedFromMatch published (willTimeoutAtTick = null).
        val disco = eventBus.publishedOfType<PlayerDisconnectedFromMatch>()
        assertThat(disco).hasSize(1)
        assertThat(disco.first().willTimeoutAtTick).isNull()
        assertThat(disco.first().phase).isEqualTo(MatchPhase.COUNTDOWN)
    }

    // ─── 2. ACTIVE — disconnect freezes, schedules timeout ───────────────

    @Test
    fun `PlayerQuit during ACTIVE freezes session and publishes Disconnected with timeout`() {
        sessions.upsert(PlayerSession(playerId, SessionState.InMatch, currentMatch = matchId))
        matches.save(buildMatch(MatchPhase.ACTIVE))

        eventBus.publish(PlayerQuitServer(playerId))

        // State flipped to Disconnected; currentMatch preserved.
        val after = sessions.find(playerId)
        assertThat(after).isNotNull
        assertThat(after!!.state).isEqualTo(SessionState.Disconnected)
        assertThat(after.currentMatch).isEqualTo(matchId)

        // Lobby spawn NOT called yet (player still pending reconnect).
        assertThat(worldGateway.spawnCalls).isEmpty()

        // Disconnect event has timeout set; no terminal disconnect, no forfeit.
        val disco = eventBus.publishedOfType<PlayerDisconnectedFromMatch>()
        assertThat(disco).hasSize(1)
        assertThat(disco.first().willTimeoutAtTick).isEqualTo(1000L + 3600L)
        assertThat(disco.first().phase).isEqualTo(MatchPhase.ACTIVE)
        assertThat(eventBus.publishedOfType<PlayerForfeited>()).isEmpty()
    }

    // ─── 3. ACTIVE — reconnect within window resumes ──────────────────────

    @Test
    fun `PlayerJoined within window resumes the match and publishes Reconnected`() {
        sessions.upsert(PlayerSession(playerId, SessionState.InMatch, currentMatch = matchId))
        matches.save(buildMatch(MatchPhase.ACTIVE))

        eventBus.publish(PlayerQuitServer(playerId))
        // Reconnect after a small gap (well under 3 min).
        scheduler.advanceTicks(200L)
        eventBus.publish(PlayerJoinedServer(playerId))

        // Session restored to InMatch.
        val after = sessions.find(playerId)
        assertThat(after).isNotNull
        assertThat(after!!.state).isEqualTo(SessionState.InMatch)
        assertThat(after.currentMatch).isEqualTo(matchId)

        // Reconnected event fired.
        val recon = eventBus.publishedOfType<PlayerReconnectedToMatch>()
        assertThat(recon).hasSize(1)
        assertThat(recon.first().matchId).isEqualTo(matchId)

        // spectatorSpawn was used as the rejoin teleport target.
        assertThat(worldGateway.spawnCalls)
            .anyMatch { it.playerId == playerId && it.target == "match:${matchId.value}" }

        // Advance past the original timeout — the scheduled callback must see
        // state ≠ Disconnected and skip the removal. Net: still InMatch, no
        // extra terminal disconnect events.
        scheduler.advanceTicks(3600L)
        assertThat(sessions.find(playerId)!!.state).isEqualTo(SessionState.InMatch)
        val terminalDisco = eventBus.publishedOfType<PlayerDisconnectedFromMatch>()
            .filter { it.willTimeoutAtTick == null }
        assertThat(terminalDisco).isEmpty()
    }

    // ─── 4. ACTIVE — timeout elapses without reconnect → kick ─────────────

    @Test
    fun `PlayerQuit during ACTIVE plus timeout elapses without rejoin kicks player`() {
        sessions.upsert(PlayerSession(playerId, SessionState.InMatch, currentMatch = matchId))
        matches.save(buildMatch(MatchPhase.ACTIVE))

        eventBus.publish(PlayerQuitServer(playerId))
        // Advance past the 3-min window (3600 ticks).
        scheduler.advanceTicks(3600L)

        // Session reset to Lobby; currentMatch cleared.
        val after = sessions.find(playerId)
        assertThat(after).isNotNull
        assertThat(after!!.state).isEqualTo(SessionState.Lobby)
        assertThat(after.currentMatch).isNull()

        // Lobby spawn was called by the timeout-driven RemoveFromMatchUseCase.
        assertThat(worldGateway.spawnCalls)
            .anyMatch { it.playerId == playerId && it.target == "lobby" }

        // Two PlayerDisconnectedFromMatch events: initial (timeout!=null) +
        // terminal (timeout==null) from the timeout-fired RemoveFromMatchUseCase.
        val disco = eventBus.publishedOfType<PlayerDisconnectedFromMatch>()
        assertThat(disco).hasSize(2)
        assertThat(disco.any { it.willTimeoutAtTick != null }).isTrue()
        assertThat(disco.any { it.willTimeoutAtTick == null }).isTrue()
    }

    // ─── 5. SUDDEN_DEATH — disconnect forfeits ─────────────────────────────

    @Test
    fun `PlayerQuit during SUDDEN_DEATH escalates to forfeit immediately`() {
        sessions.upsert(PlayerSession(playerId, SessionState.InMatch, currentMatch = matchId))
        matches.save(buildMatch(MatchPhase.SUDDEN_DEATH))

        eventBus.publish(PlayerQuitServer(playerId))

        // Session reset (forfeit removes immediately).
        val after = sessions.find(playerId)
        assertThat(after!!.state).isEqualTo(SessionState.Lobby)
        assertThat(after.currentMatch).isNull()

        // PlayerForfeited published.
        val forfeit = eventBus.publishedOfType<PlayerForfeited>()
        assertThat(forfeit).hasSize(1)
        assertThat(forfeit.first().playerId).isEqualTo(playerId)
        assertThat(forfeit.first().matchId).isEqualTo(matchId)

        // Lobby spawn was called.
        assertThat(worldGateway.spawnCalls)
            .anyMatch { it.target == "lobby" }
    }

    // ─── 6. ENDED — no-op ──────────────────────────────────────────────────

    @Test
    fun `PlayerQuit during ENDED is a no-op`() {
        sessions.upsert(PlayerSession(playerId, SessionState.InMatch, currentMatch = matchId))
        matches.save(buildMatch(MatchPhase.ENDED))

        eventBus.publish(PlayerQuitServer(playerId))

        // State unchanged.
        val after = sessions.find(playerId)
        assertThat(after!!.state).isEqualTo(SessionState.InMatch)
        assertThat(after.currentMatch).isEqualTo(matchId)

        // No spawn, no disco, no forfeit.
        assertThat(worldGateway.spawnCalls).isEmpty()
        assertThat(eventBus.publishedOfType<PlayerDisconnectedFromMatch>()).isEmpty()
        assertThat(eventBus.publishedOfType<PlayerForfeited>()).isEmpty()
    }

    // ─── 7. No session — no-op ─────────────────────────────────────────────

    @Test
    fun `PlayerQuit for untracked player is silently ignored`() {
        // Nothing in sessions / matches.

        eventBus.publish(PlayerQuitServer(playerId))

        assertThat(sessions.find(playerId)).isNull()
        assertThat(eventBus.publishedOfType<PlayerDisconnectedFromMatch>()).isEmpty()
    }

    // ─── 8. Reconnect with no Disconnected state — no-op ───────────────────

    @Test
    fun `PlayerJoined for non-disconnected session is a no-op`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Lobby, currentMatch = null))

        eventBus.publish(PlayerJoinedServer(playerId))

        // State unchanged, no resume, no spawn.
        assertThat(sessions.find(playerId)!!.state).isEqualTo(SessionState.Lobby)
        assertThat(eventBus.publishedOfType<PlayerReconnectedToMatch>()).isEmpty()
        assertThat(worldGateway.spawnCalls).isEmpty()
    }
}
