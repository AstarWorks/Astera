package com.astarworks.astera.match.scenario

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.eventbus.listeners.lobby.TeleportToLobbyOnMatchEndedListener
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier
import com.astarworks.astera.match.application.service.InMemoryMatchModeRegistry
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.matchmaking.InMemoryMatchmaker
import com.astarworks.astera.match.application.service.matchmaking.QueueMatchScheduler
import com.astarworks.astera.match.application.usecase.match.EndMatchUseCase
import com.astarworks.astera.match.application.usecase.match.EntryGameUseCase
import com.astarworks.astera.match.application.usecase.match.PhaseTimerUseCase
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFoundUseCase
import com.astarworks.astera.match.application.usecase.matchmaking.MatchPlan
import com.astarworks.astera.match.application.service.DefaultSpawnSelector
import com.astarworks.astera.match.application.usecase.spawn.MatchSpawnUseCase
import com.astarworks.astera.match.domain.match.MatchMinPlayerPolicy
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueLane
import com.astarworks.astera.match.domain.matchmaking.QueueSettings
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP8: queue -> scheduled match formation -> auto start/entry/teleport ->
 * COUNTDOWN to ACTIVE -> end -> lobby, against the in-memory fakes (hand-wired
 * the same way `MatchWiring` / `MatchGameplayWiring` do it).
 */
class QueueToMatchLifecycleScenarioTest {

    private val arena = StageId("arena")

    private class Harness(settings: QueueSettings, val arena: StageId) {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val matches = InMemoryMatchRepository()
        val world = FakeWorldGateway()
        val notes = mutableListOf<Pair<PlayerId, String>>()
        val matchmaker = InMemoryMatchmaker(eventBus = bus, sizeByMode = { settings.sizeFor(it) })
        val lifecycle: IMatchLifecycle

        init {
            val stages = StageContents().also {
                it.register(
                    Stage(
                        id = arena,
                        displayNameKey = MessageKey("astera.stage.arena"),
                        regions = emptyList(),
                        spawnPoints = listOf(
                            SpawnPoint(WorldId("world"), Vec3(-45.0, -60.0, 0.0), role = SpawnRole.TEAM, team = TeamId("red")),
                            SpawnPoint(WorldId("world"), Vec3(45.0, -60.0, 0.0), role = SpawnRole.TEAM, team = TeamId("blue")),
                        ),
                    ),
                )
            }
            val modes = InMemoryMatchModeRegistry()
            bus.subscribe(MatchStarted::class) { modes.register(it.matchId, it.modeKey) }
            val start = StartMatchUseCase(stages, matches, bus, scheduler)
            val end = EndMatchUseCase(matches, bus, scheduler)
            val entry = EntryGameUseCase(matches, bus, MatchSpawnUseCase(matches, stages, world, bus, DefaultSpawnSelector(matches)))
            val switch = SwitchPhaseUseCase(
                matches, bus, scheduler, modes,
                minPlayersFor = { settings.minPlayersForModeKey(it) ?: MatchMinPlayerPolicy.requirementFor(it) },
            )
            val timer = PhaseTimerUseCase(
                matches, scheduler, switch, null,
                countdownTicks = { settings.matchCountdownSeconds * 20L },
            )
            scheduler.runRepeatingTicks(20L, 20L) { timer.tickAll() }
            val lobby = TeleportToLobbyOnMatchEndedListener(matches, world, bus, scheduler)
            bus.subscribe(MatchEnded::class) { lobby.handle(it) }
            bus.subscribe(PlayerQuitServer::class) { matchmaker.dequeue(it.playerId) }
            lifecycle = object : IMatchLifecycle {
                override fun start(stageId: StageId, modeKey: String?) = start.execute(stageId, modeKey = modeKey)
                override fun end(matchId: MatchId, reason: EndReason) = end.execute(matchId, reason)
                override fun entry(matchId: MatchId, playerId: PlayerId, teamId: TeamId?) =
                    entry.execute(matchId, playerId, teamId)
                override fun switchPhase(matchId: MatchId, trigger: MatchStateMachine.TransitionTrigger) =
                    switch.execute(matchId, trigger)
            }
            val notifier = IQueueNotifier { id, key, _ -> notes += id to key.value }
            val found = MatchFoundUseCase(matchmaker, lifecycle, matches, notifier) { MatchPlan(arena, null) }
            QueueMatchScheduler(matchmaker, found, notifier, scheduler) { settings }.start()
        }

        fun seconds(n: Int) = scheduler.advanceTicks(n * 20L)
        fun notes(key: String) = notes.filter { it.second == "astera.match.matchmaking.$key" }
    }

    private fun settings(min: Int, perTeam: Int = 2, queueCountdown: Int = 5) = QueueSettings(
        checkIntervalSeconds = 1,
        queueCountdownSeconds = queueCountdown,
        matchCountdownSeconds = 10,
        lanes = mapOf("norma" to QueueLane("norma", StageId("arena"), 2, perTeam, min)),
    )

    private fun pid() = PlayerId(UUID.randomUUID())

    @Test
    fun `queue to match to lobby with a lone playtester`() {
        val h = Harness(settings(min = 1), arena)
        val solo = pid()
        h.matchmaker.enqueue(solo, MatchmakingMode.Norma)

        h.seconds(3)
        assertThat(h.matches.findActiveMatches()).isEmpty() // still counting down
        h.seconds(3)

        // Match formed: player entered, on a team, teleported to a spawn.
        val match = h.matches.findActiveMatches().single()
        assertThat(match.phase).isEqualTo(MatchPhase.COUNTDOWN)
        assertThat(match.teams.flatMap { it.members }).containsExactly(solo)
        assertThat(h.world.teleports.map { it.playerId }).containsExactly(solo)
        assertThat(h.matchmaker.pendingByMode(MatchmakingMode.Norma)).isEmpty()
        assertThat(h.notes("match-found")).hasSize(1)

        // In-arena countdown elapses; a 1-player roster still satisfies min_players=1.
        h.seconds(11)
        assertThat(h.matches.findById(match.id)!!.phase).isEqualTo(MatchPhase.ACTIVE)

        // End -> back to lobby; the player can queue again.
        h.lifecycle.end(match.id, EndReason.AdminEnded)
        assertThat(h.matches.findById(match.id)!!.phase).isEqualTo(MatchPhase.ENDED)
        assertThat(h.world.spawnCalls.filter { it.target == "lobby" }.map { it.playerId }).containsExactly(solo)
        assertThat(h.matches.findActiveMatches()).isEmpty()
        assertThat(h.matchmaker.enqueue(solo, MatchmakingMode.Norma).isSuccess).isTrue
    }

    @Test
    fun `four queued players are split two and two and a full queue starts immediately`() {
        val h = Harness(settings(min = 2, perTeam = 2, queueCountdown = 30), arena)
        val players = List(4) { pid() }
        players.forEach { h.matchmaker.enqueue(it, MatchmakingMode.Norma) }

        h.seconds(2) // far below the 30s countdown, but the lane is full
        val match = h.matches.findActiveMatches().single()
        assertThat(match.teams.map { it.members.size }).containsExactly(2, 2)
        assertThat(match.teams.flatMap { it.members }).containsExactlyInAnyOrderElementsOf(players)
        assertThat(h.world.teleports).hasSize(4)
    }

    @Test
    fun `countdown resets when a queued player leaves and cancels below min`() {
        val h = Harness(settings(min = 2, perTeam = 4, queueCountdown = 10), arena)
        val a = pid(); val b = pid(); val c = pid()
        listOf(a, b, c).forEach { h.matchmaker.enqueue(it, MatchmakingMode.Norma) }

        h.seconds(8) // 2s left on the countdown
        assertThat(h.matches.findActiveMatches()).isEmpty()
        h.bus.publish(PlayerQuitServer(playerId = c)) // leaves: countdown restarts
        h.seconds(4)
        assertThat(h.matches.findActiveMatches()).isEmpty() // would have started without the reset
        h.seconds(7)
        assertThat(h.matches.findActiveMatches().single().teams.flatMap { it.members })
            .containsExactlyInAnyOrder(a, b)

        // Fresh lane: below min the countdown is cancelled and announced.
        val h2 = Harness(settings(min = 2, perTeam = 4, queueCountdown = 10), arena)
        val x = pid(); val y = pid()
        h2.matchmaker.enqueue(x, MatchmakingMode.Norma); h2.matchmaker.enqueue(y, MatchmakingMode.Norma)
        h2.seconds(3)
        h2.matchmaker.dequeue(y)
        h2.seconds(2)
        assertThat(h2.notes("countdown-cancelled").map { it.first }).contains(x)
        assertThat(h2.matches.findActiveMatches()).isEmpty()
    }

    @Test
    fun `a busy stage holds the next formation until the running match ends`() {
        val h = Harness(settings(min = 1, perTeam = 1, queueCountdown = 1), arena)
        h.matchmaker.enqueue(pid(), MatchmakingMode.Norma)
        h.matchmaker.enqueue(pid(), MatchmakingMode.Norma)
        h.seconds(2)
        val first = h.matches.findActiveMatches().single()

        val late = pid()
        h.matchmaker.enqueue(late, MatchmakingMode.Norma)
        h.seconds(5)
        assertThat(h.matches.findActiveMatches()).hasSize(1) // still only the first
        assertThat(h.matchmaker.queueStatus(late)).isNotNull
        assertThat(h.notes("stage-busy").map { it.first }).containsExactly(late)

        h.lifecycle.end(first.id, EndReason.AdminEnded)
        h.seconds(3)
        val second = h.matches.findActiveMatches().single()
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(second.teams.flatMap { it.members }).containsExactly(late)
    }
}
