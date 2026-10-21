package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
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
    fun `Heal is reported as not implemented`() {
        val heal = Mechanic.Heal(Targeter.Caster, NumericValue.Literal(5.0))
        assertThat(MechanicSupport.problemsOf(heal)).anyMatch { "Heal" in it && "not implemented" in it }
    }

    @Test
    fun `CEL value inside a mechanic is reported`() {
        val m = Mechanic.PlaySound(Targeter.Caster, SoundId("entity.blaze.shoot"), volume = NumericValue.Cel("1.0 + 1.0"))
        assertThat(MechanicSupport.problemsOf(m)).anyMatch { "CEL" in it }
    }

    @Test
    fun `CEL condition and always-false condition are reported`() {
        val cel = Mechanic.PlaySound(Targeter.Caster, SoundId("entity.blaze.shoot"), condition = Condition.Cel("true"))
        assertThat(MechanicSupport.problemsOf(cel)).anyMatch { "CEL condition" in it }
        val health = Mechanic.PlaySound(
            Targeter.Caster, SoundId("entity.blaze.shoot"),
            condition = Condition.Not(Condition.HealthBelow(Targeter.Caster, NumericValue.Literal(5.0))),
        )
        assertThat(MechanicSupport.problemsOf(health)).anyMatch { "HealthBelow" in it }
    }

    @Test
    fun `unsupported mechanic nested in a control-flow child is found`() {
        val heal = Mechanic.Heal(Targeter.Caster, NumericValue.Literal(5.0))
        val loop = Mechanic.Loop(Targeter.Caster, count = com.astarworks.astera.core.domain.skill.value.IntValue.Literal(2), mechanics = listOf(sound, heal))
        assertThat(MechanicSupport.problemsOf(loop)).anyMatch { "Heal" in it }
    }

    @Test
    fun `team-filter targeter is reported`() {
        val m = Mechanic.PlaySound(Targeter.Enemies(Targeter.Caster), SoundId("entity.blaze.shoot"))
        assertThat(MechanicSupport.problemsOf(m)).anyMatch { "Enemies" in it }
    }
}
