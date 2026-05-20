package com.astarworks.astera.core.domain.skill.condition

import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ConditionTest {

    @Test
    fun `HasStatus carries targeter and status id`() {
        val c = Condition.HasStatus(Targeter.Target, StatusEffectId("burn"))
        assertThat(c.targets).isEqualTo(Targeter.Target)
        assertThat(c.statusId).isEqualTo(StatusEffectId("burn"))
    }

    @Test
    fun `IsAlly and IsEnemy carry both targeters`() {
        val ally = Condition.IsAlly(Targeter.Caster, Targeter.Target)
        assertThat(ally.a).isEqualTo(Targeter.Caster)
        assertThat(ally.b).isEqualTo(Targeter.Target)

        val enemy = Condition.IsEnemy(Targeter.Caster, Targeter.Target)
        assertThat(enemy.a).isEqualTo(Targeter.Caster)
        assertThat(enemy.b).isEqualTo(Targeter.Target)
    }

    @Test
    fun `HealthBelow carries targets and fraction`() {
        val c = Condition.HealthBelow(Targeter.Target, NumericValue.Literal(0.3))
        assertThat(c.targets).isEqualTo(Targeter.Target)
        assertThat(c.fraction).isEqualTo(NumericValue.Literal(0.3))
    }

    @Test
    fun `DistanceLessThan carries both targeters and a distance`() {
        val c = Condition.DistanceLessThan(
            a = Targeter.Caster,
            b = Targeter.Target,
            distance = NumericValue.Literal(5.0),
        )
        assertThat(c.a).isEqualTo(Targeter.Caster)
        assertThat(c.b).isEqualTo(Targeter.Target)
        assertThat(c.distance).isEqualTo(NumericValue.Literal(5.0))
    }

    @Test
    fun `And Or Not compose to a three-deep tree`() {
        val leaf1 = Condition.IsEnemy(Targeter.Caster, Targeter.Target)
        val leaf2 = Condition.HealthBelow(Targeter.Target, NumericValue.Literal(0.5))
        val leaf3 = Condition.HasStatus(Targeter.Target, StatusEffectId("burn"))
        val notLeaf3 = Condition.Not(leaf3)
        val orInner = Condition.Or(leaf2, notLeaf3)
        val tree = Condition.And(leaf1, orInner)

        assertThat(tree.left).isEqualTo(leaf1)
        assertThat(tree.right).isEqualTo(orInner)
        assertThat((tree.right as Condition.Or).left).isEqualTo(leaf2)
        assertThat(((tree.right as Condition.Or).right as Condition.Not).inner).isEqualTo(leaf3)
    }

    @Test
    fun `Cel rejects blank script`() {
        assertThatThrownBy { Condition.Cel("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Condition.Cel("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Cel carries script and bindings`() {
        val c = Condition.Cel("target.hp < 5.0")
        assertThat(c.script).isEqualTo("target.hp < 5.0")
        assertThat(c.referencedBindings).isEmpty()
    }
}
