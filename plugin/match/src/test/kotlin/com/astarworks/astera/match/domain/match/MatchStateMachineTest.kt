package com.astarworks.astera.match.domain.match

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MatchStateMachineTest {

    // ── Happy paths ──────────────────────────────────────────────────────────

    @Test
    fun `WAITING + Start transitions to COUNTDOWN`() {
        val result = MatchStateMachine.transition(MatchPhase.WAITING, MatchStateMachine.TransitionTrigger.Start)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(MatchPhase.COUNTDOWN)
    }

    @Test
    fun `COUNTDOWN + CountdownComplete transitions to ACTIVE`() {
        val result = MatchStateMachine.transition(MatchPhase.COUNTDOWN, MatchStateMachine.TransitionTrigger.CountdownComplete)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(MatchPhase.ACTIVE)
    }

    @Test
    fun `ACTIVE + ScoreThresholdReached transitions to SUDDEN_DEATH`() {
        val result = MatchStateMachine.transition(MatchPhase.ACTIVE, MatchStateMachine.TransitionTrigger.ScoreThresholdReached)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(MatchPhase.SUDDEN_DEATH)
    }

    @Test
    fun `SUDDEN_DEATH + SuddenDeathTimer transitions to ENDED`() {
        val result = MatchStateMachine.transition(MatchPhase.SUDDEN_DEATH, MatchStateMachine.TransitionTrigger.SuddenDeathTimer)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(MatchPhase.ENDED)
    }

    @Test
    fun `WAITING + Cancel transitions to ENDED`() {
        val result = MatchStateMachine.transition(MatchPhase.WAITING, MatchStateMachine.TransitionTrigger.Cancel)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(MatchPhase.ENDED)
    }

    @Test
    fun `COUNTDOWN + Cancel transitions back to WAITING`() {
        val result = MatchStateMachine.transition(MatchPhase.COUNTDOWN, MatchStateMachine.TransitionTrigger.Cancel)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(MatchPhase.WAITING)
    }

    @Test
    fun `ACTIVE + AdminEnd transitions to ENDED`() {
        val result = MatchStateMachine.transition(MatchPhase.ACTIVE, MatchStateMachine.TransitionTrigger.AdminEnd)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(MatchPhase.ENDED)
    }

    @Test
    fun `SUDDEN_DEATH + AdminEnd transitions to ENDED`() {
        val result = MatchStateMachine.transition(MatchPhase.SUDDEN_DEATH, MatchStateMachine.TransitionTrigger.AdminEnd)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(MatchPhase.ENDED)
    }

    // ── Illegal transitions ──────────────────────────────────────────────────

    @Test
    fun `ENDED + any trigger returns MatchAlreadyEnded`() {
        val result = MatchStateMachine.transition(MatchPhase.ENDED, MatchStateMachine.TransitionTrigger.Start)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(MatchTransitionError.MatchAlreadyEnded::class.java)
    }

    @Test
    fun `WAITING + CountdownComplete returns IllegalTransition`() {
        val result = MatchStateMachine.transition(MatchPhase.WAITING, MatchStateMachine.TransitionTrigger.CountdownComplete)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(MatchTransitionError.IllegalTransition::class.java)
        assertThat((error as MatchTransitionError.IllegalTransition).from).isEqualTo(MatchPhase.WAITING)
    }

    @Test
    fun `ACTIVE + Start returns IllegalTransition`() {
        val result = MatchStateMachine.transition(MatchPhase.ACTIVE, MatchStateMachine.TransitionTrigger.Start)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(MatchTransitionError.IllegalTransition::class.java)
    }

    @Test
    fun `ACTIVE + Cancel returns IllegalTransition`() {
        val result = MatchStateMachine.transition(MatchPhase.ACTIVE, MatchStateMachine.TransitionTrigger.Cancel)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(MatchTransitionError.IllegalTransition::class.java)
    }

    @Test
    fun `WAITING + AdminEnd returns IllegalTransition`() {
        val result = MatchStateMachine.transition(MatchPhase.WAITING, MatchStateMachine.TransitionTrigger.AdminEnd)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(MatchTransitionError.IllegalTransition::class.java)
    }

    @Test
    fun `COUNTDOWN + ScoreThresholdReached returns IllegalTransition`() {
        val result = MatchStateMachine.transition(MatchPhase.COUNTDOWN, MatchStateMachine.TransitionTrigger.ScoreThresholdReached)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(MatchTransitionError.IllegalTransition::class.java)
    }
}
