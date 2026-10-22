package com.astarworks.astera.match.application.admin

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.matchmaking.InMemoryMatchmaker
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.matchmaking.MatchSizeSpec
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** F1-D: the `minPlayers` / `only` overrides the admin panel's force-start uses, and phase advancing. */
class AdminMatchmakerOverridesTest {

    private val matchmaker = InMemoryMatchmaker(
        eventBus = FakeDomainEventBus(),
        sizeByMode = { MatchSizeSpec(teamCount = 2, playersPerTeam = 4, minPlayers = 4) },
    )
    private fun id() = PlayerId(UUID.randomUUID())

    @Test
    fun `lane floor blocks a short queue but a minPlayers override forms it`() {
        val a = id()
        matchmaker.enqueue(a, MatchmakingMode.Norma)

        assertThat((matchmaker.tryFormMatch(MatchmakingMode.Norma) as Result.Success).value).isNull()
        val formation = (matchmaker.tryFormMatch(MatchmakingMode.Norma, minPlayers = 1) as Result.Success).value
        assertThat(formation!!.players).containsExactly(a)
        assertThat(matchmaker.pendingByMode(MatchmakingMode.Norma)).isEmpty()
    }

    @Test
    fun `only restricts the draw to the named players and leaves the rest queued`() {
        val admin = id()
        val other = id()
        matchmaker.enqueue(other, MatchmakingMode.Norma)
        matchmaker.enqueue(admin, MatchmakingMode.Norma)

        val formation = (matchmaker.tryFormMatch(MatchmakingMode.Norma, minPlayers = 1, only = setOf(admin)) as Result.Success).value

        assertThat(formation!!.players).containsExactly(admin)
        assertThat(matchmaker.pendingByMode(MatchmakingMode.Norma).map { it.playerId }).containsExactly(other)
    }

    @Test
    fun `next trigger advances every non-ended phase and nothing after ENDED`() {
        assertThat(AdminMatchControlUseCase.nextTrigger(MatchPhase.WAITING)).isEqualTo(MatchStateMachine.TransitionTrigger.Start)
        assertThat(AdminMatchControlUseCase.nextTrigger(MatchPhase.COUNTDOWN)).isEqualTo(MatchStateMachine.TransitionTrigger.CountdownComplete)
        assertThat(AdminMatchControlUseCase.nextTrigger(MatchPhase.ACTIVE)).isEqualTo(MatchStateMachine.TransitionTrigger.ScoreThresholdReached)
        assertThat(AdminMatchControlUseCase.nextTrigger(MatchPhase.SUDDEN_DEATH)).isEqualTo(MatchStateMachine.TransitionTrigger.SuddenDeathTimer)
        assertThat(AdminMatchControlUseCase.nextTrigger(MatchPhase.ENDED)).isNull()
        // Every advertised trigger is legal from its phase.
        MatchPhase.entries.filter { it != MatchPhase.ENDED }.forEach { phase ->
            val t = AdminMatchControlUseCase.nextTrigger(phase)!!
            assertThat(MatchStateMachine.transition(phase, t)).isInstanceOf(Result.Success::class.java)
        }
    }
}
