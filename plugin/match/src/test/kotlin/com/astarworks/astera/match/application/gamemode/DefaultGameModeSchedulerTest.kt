package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.service.DefaultGameModeScheduler
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * ADR-0106 Wave 5gamemode — [DefaultGameModeScheduler] behaviour tests.
 *
 *  - register/unregister add/remove tracked match ids
 *  - register schedules a repeating task at the mode's tickPeriodTicks
 *  - tickPeriodTicks == 0 means "track but don't schedule" (event-driven modes)
 *  - re-register cancels the prior task (idempotent + hot-swap safe)
 */
class DefaultGameModeSchedulerTest {

    private val matchA = MatchId("match-a")
    private val matchB = MatchId("match-b")

    @Test
    fun `register tracks the match and unregister removes it`() {
        val scheduler = FakeScheduler()
        val sut = DefaultGameModeScheduler(scheduler)
        val mode = RecordingMode(periodTicks = 20L)

        val ctx = GameModeContext(matchA, mode.id())
        sut.register(matchA, mode, ctx)
        assertThat(sut.activeMatches()).containsExactly(matchA)

        sut.unregister(matchA)
        assertThat(sut.activeMatches()).isEmpty()
    }

    @Test
    fun `register schedules onTick at the mode tickPeriodTicks`() {
        val scheduler = FakeScheduler()
        val sut = DefaultGameModeScheduler(scheduler)
        val mode = RecordingMode(periodTicks = 20L)
        val ctx = GameModeContext(matchA, mode.id())

        sut.register(matchA, mode, ctx)

        scheduler.advanceTicks(60L)
        // Initial delay = 20, period = 20: fires at t=20, 40, 60.
        assertThat(mode.ticks).hasSize(3)
    }

    @Test
    fun `tickPeriodTicks of 0 tracks the match without scheduling a task`() {
        val scheduler = FakeScheduler()
        val sut = DefaultGameModeScheduler(scheduler)
        val mode = RecordingMode(periodTicks = 0L)
        val ctx = GameModeContext(matchA, mode.id())

        sut.register(matchA, mode, ctx)
        scheduler.advanceTicks(200L)

        assertThat(sut.activeMatches()).containsExactly(matchA)
        assertThat(mode.ticks).isEmpty()
    }

    @Test
    fun `re-register cancels the prior task`() {
        val scheduler = FakeScheduler()
        val sut = DefaultGameModeScheduler(scheduler)
        val mode = RecordingMode(periodTicks = 20L)
        val ctx = GameModeContext(matchA, mode.id())

        sut.register(matchA, mode, ctx)
        scheduler.advanceTicks(40L) // fires at t=20, t=40

        // Re-register: prior task cancelled, new task scheduled (initial delay 20).
        val mode2 = RecordingMode(periodTicks = 40L)
        sut.register(matchA, mode2, ctx)
        scheduler.advanceTicks(80L)
        // The original mode should not see new ticks; only mode2 fires.
        assertThat(mode.ticks).hasSize(2)
        // mode2 with periodTicks=40 fires at t=40+20+x... initial delay is 40,
        // then period 40. From advance-start point of mode2 (t=40), ticks at
        // 80, 120. But we only advance to 120 → 2 fires.
        assertThat(mode2.ticks).hasSize(2)
    }

    @Test
    fun `register tracks multiple matches independently`() {
        val scheduler = FakeScheduler()
        val sut = DefaultGameModeScheduler(scheduler)
        val modeA = RecordingMode(periodTicks = 20L)
        val modeB = RecordingMode(periodTicks = 40L)

        sut.register(matchA, modeA, GameModeContext(matchA, modeA.id()))
        sut.register(matchB, modeB, GameModeContext(matchB, modeB.id()))

        assertThat(sut.activeMatches()).containsExactlyInAnyOrder(matchA, matchB)

        scheduler.advanceTicks(80L)
        // A: every 20 → t=20,40,60,80 → 4 ticks
        // B: every 40 → t=40,80 → 2 ticks
        assertThat(modeA.ticks).hasSize(4)
        assertThat(modeB.ticks).hasSize(2)
    }

    @Test
    fun `tick crash does not propagate to the scheduler`() {
        val scheduler = FakeScheduler()
        val sut = DefaultGameModeScheduler(scheduler)
        val mode = ThrowingMode()
        val ctx = GameModeContext(matchA, mode.id())

        sut.register(matchA, mode, ctx)

        // Expectation: advance does not throw. The framework swallows tick
        // exceptions per IGameMode kdoc Lifecycle contract.
        scheduler.advanceTicks(60L)
        assertThat(mode.calls).isGreaterThan(0)
    }

    /** Test mode that records every onTick invocation. */
    private class RecordingMode(private val periodTicks: Long) : IGameMode {
        val ticks: MutableList<Long> = mutableListOf()
        private val gid = GameModeId("test-${Math.abs(System.identityHashCode(this))}")
        override fun id(): GameModeId = gid
        override fun titleKey(): MessageKey = MessageKey("test")
        override fun defaultPhases(): List<IGameModePhase> = emptyList()
        override fun tickPeriodTicks(): Long = periodTicks
        override fun onMatchStarted(context: GameModeContext) {}
        override fun onPhaseChanged(context: GameModeContext, oldPhase: IGameModePhase, newPhase: IGameModePhase) {}
        override fun onTick(context: GameModeContext, tick: Long) { ticks += tick }
        override fun onMatchEnded(context: GameModeContext) {}
    }

    /** Test mode that throws every tick; framework must swallow. */
    private class ThrowingMode : IGameMode {
        var calls: Int = 0
        override fun id(): GameModeId = GameModeId("throwing-mode")
        override fun titleKey(): MessageKey = MessageKey("test")
        override fun defaultPhases(): List<IGameModePhase> = emptyList()
        override fun tickPeriodTicks(): Long = 20L
        override fun onMatchStarted(context: GameModeContext) {}
        override fun onPhaseChanged(context: GameModeContext, oldPhase: IGameModePhase, newPhase: IGameModePhase) {}
        override fun onTick(context: GameModeContext, tick: Long) {
            calls++
            throw RuntimeException("boom on tick $tick")
        }
        override fun onMatchEnded(context: GameModeContext) {}
    }
}
