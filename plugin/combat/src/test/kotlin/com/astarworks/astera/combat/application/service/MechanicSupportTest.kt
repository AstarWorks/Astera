package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.targeter.LocationRef
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.skill.value.NumericValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MechanicSupportTest {

    private val sound = Mechanic.PlaySound(Targeter.Caster, SoundId("entity.blaze.shoot"))

    @Test
    fun `implemented mechanic has no problems`() {
        assertThat(MechanicSupport.problemsOf(sound)).isEmpty()
    }

    @Test
    fun `Resurrect is reported as not implemented`() {
        val heal = Mechanic.Resurrect(Targeter.Caster, NumericValue.Literal(0.5))
        assertThat(MechanicSupport.problemsOf(heal)).anyMatch { "Resurrect" in it && "not implemented" in it }
    }

    @Test
    fun `CEL value inside a mechanic is reported`() {
        val m = Mechanic.PlaySound(Targeter.Caster, SoundId("entity.blaze.shoot"), volume = NumericValue.Cel("1.0 + 1.0"))
        assertThat(MechanicSupport.problemsOf(m)).anyMatch { "CEL" in it }
    }

    @Test
    fun `CEL condition is reported`() {
        val cel = Mechanic.PlaySound(Targeter.Caster, SoundId("entity.blaze.shoot"), condition = Condition.Cel("true"))
        assertThat(MechanicSupport.problemsOf(cel)).anyMatch { "CEL condition" in it }
    }

    @Test
    fun `entity-state conditions and team targeters are supported`() {
        val conditions = listOf(
            Condition.HealthBelow(Targeter.Caster, NumericValue.Literal(0.5)),
            Condition.IsAlly(Targeter.Caster, Targeter.Target),
            Condition.IsEnemy(Targeter.Caster, Targeter.Target),
            Condition.DistanceLessThan(Targeter.Caster, Targeter.Target, NumericValue.Literal(5.0)),
            Condition.HasStatus(Targeter.Target, StatusEffectId("burn")),
        )
        for (condition in conditions) {
            val m = Mechanic.PlaySound(Targeter.Enemies(Targeter.Caster), SoundId("entity.blaze.shoot"), condition = condition)
            assertThat(MechanicSupport.problemsOf(m)).describedAs(condition.toString()).isEmpty()
        }
    }

    @Test
    fun `WP4 mechanics are supported`() {
        val zero = IntValue.Literal(0)
        val mechanics = listOf(
            Mechanic.Cleanse(Targeter.Caster),
            Mechanic.Ignite(Targeter.Target, zero),
            Mechanic.Freeze(Targeter.Target, zero),
            Mechanic.Silence(Targeter.Target, zero),
            Mechanic.SetVelocity(Targeter.Caster, DirectionRef.Up, NumericValue.Literal(1.0)),
            Mechanic.Propel(Targeter.Caster, DirectionRef.FromCaster, NumericValue.Literal(1.0)),
            Mechanic.Leap(Targeter.Caster, LocationRef.Target),
            Mechanic.SetCooldown(Targeter.Caster, SkillId("s"), zero),
            Mechanic.ModifyAttribute(Targeter.Caster, "stat.attack_damage", NumericValue.Literal(1.0), zero),
        )
        for (m in mechanics) assertThat(MechanicSupport.problemsOf(m)).describedAs(m.toString()).isEmpty()
    }

    @Test
    fun `unsupported mechanic nested in a control-flow child is found`() {
        val heal = Mechanic.Resurrect(Targeter.Caster, NumericValue.Literal(0.5))
        val loop = Mechanic.Loop(Targeter.Caster, count = com.astarworks.astera.core.domain.skill.value.IntValue.Literal(2), mechanics = listOf(sound, heal))
        assertThat(MechanicSupport.problemsOf(loop)).anyMatch { "Resurrect" in it }
    }
}
