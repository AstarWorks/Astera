package com.astarworks.astera.core.domain.mob.boss

import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BossPhaseTransitionEvaluatorTest {

    private fun ctx(
        phase: Int = 0,
        hp: Double = 1.0,
        now: Long = 0,
        entered: Long = 0,
        attackers: Int = 1,
        mechanics: Set<SkillId> = emptySet(),
    ) = BossPhaseEvaluationContext(
        currentPhase = phase,
        hpFraction = hp,
        nowTick = now,
        phaseEnteredAtTick = entered,
        attackerCount = attackers,
        completedMechanics = mechanics,
    )

    @Test
    fun `HpThreshold fires below percentage`() {
        val t = BossPhaseTransition.HpThreshold(0, 1, hpFraction = 0.5)
        assertThat(BossPhaseTransitionEvaluator.fires(t, ctx(hp = 0.6))).isFalse()
        assertThat(BossPhaseTransitionEvaluator.fires(t, ctx(hp = 0.5))).isTrue()
        assertThat(BossPhaseTransitionEvaluator.fires(t, ctx(hp = 0.2))).isTrue()
    }

    @Test
    fun `TimerExpired fires when elapsed ticks exceed threshold`() {
        val t = BossPhaseTransition.TimerExpired(0, 1, elapsedTicks = 100)
        assertThat(
            BossPhaseTransitionEvaluator.fires(t, ctx(now = 50, entered = 0)),
        ).isFalse()
        assertThat(
            BossPhaseTransitionEvaluator.fires(t, ctx(now = 100, entered = 0)),
        ).isTrue()
    }

    @Test
    fun `MultiPlayerThreshold fires when attacker count reaches min`() {
        val t = BossPhaseTransition.MultiPlayerThreshold(0, 1, minPlayers = 5)
        assertThat(BossPhaseTransitionEvaluator.fires(t, ctx(attackers = 4))).isFalse()
        assertThat(BossPhaseTransitionEvaluator.fires(t, ctx(attackers = 5))).isTrue()
        assertThat(BossPhaseTransitionEvaluator.fires(t, ctx(attackers = 10))).isTrue()
    }

    @Test
    fun `MechanicCompleted fires when mechanic id present`() {
        val mech = SkillId("titan-vulnerability")
        val t = BossPhaseTransition.MechanicCompleted(0, 1, mechanicSkillId = mech)
        assertThat(BossPhaseTransitionEvaluator.fires(t, ctx(mechanics = emptySet()))).isFalse()
        assertThat(BossPhaseTransitionEvaluator.fires(t, ctx(mechanics = setOf(mech)))).isTrue()
    }

    @Test
    fun `AllOf fires only when every child fires`() {
        val mech = SkillId("titan-vulnerability")
        val t = BossPhaseTransition.AllOf(
            fromPhase = 1,
            toPhase = 2,
            children = listOf(
                BossPhaseTransition.HpThreshold(1, 2, hpFraction = 0.3),
                BossPhaseTransition.MechanicCompleted(1, 2, mechanicSkillId = mech),
            ),
        )
        // HP ok but mechanic missing
        assertThat(BossPhaseTransitionEvaluator.fires(t, ctx(phase = 1, hp = 0.2))).isFalse()
        // mechanic ok but HP too high
        assertThat(
            BossPhaseTransitionEvaluator.fires(t, ctx(phase = 1, hp = 0.5, mechanics = setOf(mech))),
        ).isFalse()
        // both
        assertThat(
            BossPhaseTransitionEvaluator.fires(t, ctx(phase = 1, hp = 0.2, mechanics = setOf(mech))),
        ).isTrue()
    }

    @Test
    fun `AllOf rejects single child at construction`() {
        assertThatThrownBy {
            BossPhaseTransition.AllOf(
                fromPhase = 0,
                toPhase = 1,
                children = listOf(BossPhaseTransition.HpThreshold(0, 1, hpFraction = 0.5)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must have >= 2")
    }

    @Test
    fun `AllOf rejects mismatched from-to in children`() {
        assertThatThrownBy {
            BossPhaseTransition.AllOf(
                fromPhase = 0,
                toPhase = 1,
                children = listOf(
                    BossPhaseTransition.HpThreshold(0, 1, hpFraction = 0.5),
                    BossPhaseTransition.HpThreshold(0, 2, hpFraction = 0.3),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must share fromPhase=0 toPhase=1")
    }

    @Test
    fun `AllOf rejects nested AllOf`() {
        val inner = BossPhaseTransition.AllOf(
            fromPhase = 0,
            toPhase = 1,
            children = listOf(
                BossPhaseTransition.HpThreshold(0, 1, hpFraction = 0.5),
                BossPhaseTransition.HpThreshold(0, 1, hpFraction = 0.3),
            ),
        )
        assertThatThrownBy {
            BossPhaseTransition.AllOf(
                fromPhase = 0,
                toPhase = 1,
                children = listOf(
                    BossPhaseTransition.HpThreshold(0, 1, hpFraction = 0.5),
                    inner,
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("nested AllOf is not allowed")
    }

    @Test
    fun `BossPhaseEvaluationContext rejects phaseEntered after now`() {
        assertThatThrownBy {
            BossPhaseEvaluationContext(
                currentPhase = 0,
                hpFraction = 1.0,
                nowTick = 50,
                phaseEnteredAtTick = 100,
                attackerCount = 1,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be <= nowTick")
    }
}
