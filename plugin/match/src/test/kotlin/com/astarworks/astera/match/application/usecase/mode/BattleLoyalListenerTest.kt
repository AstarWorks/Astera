package com.astarworks.astera.match.application.usecase.mode

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.match.domain.mode.BattleLoyal
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Wave 5c BattleLoyal listener tests.
 *
 * BattleLoyal piggybacks on `VictoryConditionEvaluator` (ADR-0027) for the
 * actual win logic, so this test file only verifies lifecycle wiring:
 * subscription creation / cancellation, no side effects.
 */
class BattleLoyalListenerTest {

    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var listener: BattleLoyalListener

    @BeforeEach
    fun setUp() {
        eventBus = FakeDomainEventBus()
        listener = BattleLoyalListener(eventBus)
    }

    @Test
    fun `subscribe returns an active subscription`() {
        val sub = listener.subscribe()

        assertThat(sub.isActive).isTrue()
    }

    @Test
    fun `subscription can be cancelled`() {
        val sub = listener.subscribe()
        sub.cancel()

        assertThat(sub.isActive).isFalse()
    }

    @Test
    fun `listener does not publish MatchEnded on PhaseChanged`() {
        listener.subscribe()
        eventBus.publish(
            PhaseChanged(
                matchId = MatchId("match-x"),
                from = MatchPhase.COUNTDOWN,
                to = MatchPhase.ACTIVE,
                atTick = 10L,
            ),
        )

        assertThat(eventBus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `mode accessor returns the configured BattleLoyal value`() {
        val custom = BattleLoyal(scoreThreshold = 25)
        val l = BattleLoyalListener(eventBus, custom)

        assertThat(l.mode()).isEqualTo(custom)
        assertThat(l.mode().scoreThreshold).isEqualTo(25)
    }

    @Test
    fun `default BattleLoyal has scoreThreshold 10`() {
        assertThat(listener.mode().scoreThreshold).isEqualTo(10)
    }
}
