package com.astarworks.astera.match.application.usecase.matchmaking

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.matchmaking.InMemoryMatchmaker
import com.astarworks.astera.match.application.usecase.match.EndMatchError
import com.astarworks.astera.match.application.usecase.match.EntryGameError
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseError
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueError
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MatchFoundUseCaseTest {

    private val matches = InMemoryMatchRepository()
    private val notified = mutableListOf<Pair<PlayerId, String>>()
    private val notifier = IQueueNotifier { id, key, _ -> notified += id to key.value }

    private class FakeLifecycle(
        var startResult: Result<MatchId, StartMatchError> = Result.Success(MatchId("match-fake")),
    ) : IMatchLifecycle {
        val starts: MutableList<StageId> = mutableListOf()
        val entries: MutableList<Triple<MatchId, PlayerId, com.astarworks.astera.core.domain.match.TeamId?>> = mutableListOf()
        override fun start(stageId: StageId, modeKey: String?): Result<MatchId, StartMatchError> {
            starts += stageId
            return startResult
        }
        override fun end(matchId: MatchId, reason: EndReason): Result<Unit, EndMatchError> = Result.Success(Unit)
        override fun entry(
            matchId: MatchId,
            playerId: PlayerId,
            teamId: com.astarworks.astera.core.domain.match.TeamId?,
        ): Result<Unit, EntryGameError> {
            entries += Triple(matchId, playerId, teamId)
            return Result.Success(Unit)
        }
        override fun switchPhase(
            matchId: MatchId,
            trigger: MatchStateMachine.TransitionTrigger,
        ): Result<MatchPhase, SwitchPhaseError> = Result.Success(MatchPhase.ACTIVE)
    }

    private fun pid() = PlayerId(UUID.randomUUID())

    @Test
    fun `execute returns null when no formation is ready`() {
        val bus = FakeDomainEventBus()
        val mm = InMemoryMatchmaker(eventBus = bus, teamCount = 2, minPlayersPerTeam = 1)
        val lc = FakeLifecycle()
        val sut = MatchFoundUseCase(mm, lc, matches, notifier) { MatchPlan(StageId("default"), null) }
        val res = sut.execute(MatchmakingMode.Norma)
        assertThat(res.isSuccess).isTrue
        assertThat(res.getOrNull()).isNull()
        assertThat(lc.starts).isEmpty()
    }

    @Test
    fun `execute starts a match and enters every player on success`() {
        val bus = FakeDomainEventBus()
        val mm = InMemoryMatchmaker(eventBus = bus, teamCount = 2, minPlayersPerTeam = 1)
        val a = pid(); val b = pid()
        mm.enqueue(a, MatchmakingMode.Norma)
        mm.enqueue(b, MatchmakingMode.Norma)

        val lc = FakeLifecycle()
        val sut = MatchFoundUseCase(mm, lc, matches, notifier) { MatchPlan(StageId("default"), null) }
        val res = sut.execute(MatchmakingMode.Norma)
        assertThat(res.isSuccess).isTrue
        assertThat(res.getOrNull()).isEqualTo(MatchId("match-fake"))
        assertThat(lc.starts).containsExactly(StageId("default"))
        assertThat(lc.entries).hasSize(2)
        assertThat(lc.entries.map { it.second }).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `execute returns QueueFailure when matchmaker rejects`() {
        val bus = FakeDomainEventBus()
        val mm = InMemoryMatchmaker(eventBus = bus)
        mm.closeMode(MatchmakingMode.Norma)
        val lc = FakeLifecycle()
        val sut = MatchFoundUseCase(mm, lc, matches, notifier) { MatchPlan(StageId("default"), null) }
        val res = sut.execute(MatchmakingMode.Norma)
        assertThat(res).isInstanceOf(Result.Failure::class.java)
        val err = (res as Result.Failure).error
        assertThat(err).isInstanceOf(MatchFoundError.QueueFailure::class.java)
        assertThat((err as MatchFoundError.QueueFailure).cause).isInstanceOf(QueueError.ModeClosed::class.java)
    }

    @Test
    fun `execute returns StartFailure when StartMatchUseCase rejects the stage`() {
        val bus = FakeDomainEventBus()
        val mm = InMemoryMatchmaker(eventBus = bus, teamCount = 2, minPlayersPerTeam = 1)
        mm.enqueue(pid(), MatchmakingMode.Norma)
        mm.enqueue(pid(), MatchmakingMode.Norma)
        val lc = FakeLifecycle(
            startResult = Result.Failure(StartMatchError.StageNotFound(StageId("missing"))),
        )
        val sut = MatchFoundUseCase(mm, lc, matches, notifier) { MatchPlan(StageId("missing"), null) }
        val res = sut.execute(MatchmakingMode.Norma)
        assertThat(res).isInstanceOf(Result.Failure::class.java)
        assertThat((res as Result.Failure).error).isInstanceOf(MatchFoundError.StartFailure::class.java)
    }

    @Test
    fun `start failure puts the consumed players back in the queue`() {
        val bus = FakeDomainEventBus()
        val mm = InMemoryMatchmaker(eventBus = bus, teamCount = 2, minPlayersPerTeam = 1)
        val a = pid(); val b = pid()
        mm.enqueue(a, MatchmakingMode.Norma)
        mm.enqueue(b, MatchmakingMode.Norma)
        val lc = FakeLifecycle(startResult = Result.Failure(StartMatchError.StageNotFound(StageId("missing"))))
        val sut = MatchFoundUseCase(mm, lc, matches, notifier) { MatchPlan(StageId("missing"), null) }
        sut.execute(MatchmakingMode.Norma)
        assertThat(mm.pendingByMode(MatchmakingMode.Norma).map { it.playerId }).containsExactlyInAnyOrder(a, b)
        assertThat(notified.map { it.second }).containsOnly("astera.match.matchmaking.start-failed")
    }
}
