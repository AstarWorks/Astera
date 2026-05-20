package com.astarworks.astera.core.domain.skill.mechanic

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MechanicTest {

    @Test
    fun `Damage requires at least one component`() {
        assertThatThrownBy {
            Mechanic.Damage(
                targets = Targeter.Target,
                components = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Damage carries components and timing defaults`() {
        val m = Mechanic.Damage(
            targets = Targeter.Target,
            components = listOf(
                DamageComponent(NumericValue.Literal(5.0), DamageAttribute.FIRE),
            ),
        )
        assertThat(m.targets).isEqualTo(Targeter.Target)
        assertThat(m.components).hasSize(1)
        assertThat(m.delayTicks).isEqualTo(IntValue.Literal(0))
        assertThat(m.periodTicks).isEqualTo(IntValue.Literal(0))
        assertThat(m.durationTicks).isEqualTo(IntValue.Literal(0))
        assertThat(m.condition).isNull()
    }

    @Test
    fun `Knockback carries direction and strength`() {
        val m = Mechanic.Knockback(
            targets = Targeter.Target,
            direction = DirectionRef.FromCaster,
            strength = NumericValue.Literal(1.5),
        )
        assertThat(m.direction).isEqualTo(DirectionRef.FromCaster)
        assertThat(m.strength).isEqualTo(NumericValue.Literal(1.5))
    }

    @Test
    fun `ApplyStatus distinguishes effectDurationTicks from parent durationTicks`() {
        val m = Mechanic.ApplyStatus(
            targets = Targeter.Target,
            statusId = StatusEffectId("burn"),
            effectDurationTicks = IntValue.Literal(60),
            durationTicks = IntValue.Literal(0),
        )
        assertThat(m.effectDurationTicks).isEqualTo(IntValue.Literal(60))
        assertThat(m.durationTicks).isEqualTo(IntValue.Literal(0))
    }

    @Test
    fun `SpawnParticle accepts ParticleId and integer count`() {
        val m = Mechanic.SpawnParticle(
            targets = Targeter.HitLocation,
            particleId = ParticleId("flame-burst"),
            count = IntValue.Literal(8),
        )
        assertThat(m.particleId.value).isEqualTo("flame-burst")
        assertThat(m.count).isEqualTo(IntValue.Literal(8))
    }

    @Test
    fun `CastSkill carries skillId`() {
        val m = Mechanic.CastSkill(
            targets = Targeter.Caster,
            skillId = SkillId("fireball"),
        )
        assertThat(m.skillId.value).isEqualTo("fireball")
    }

    @Test
    fun `Loop rejects empty mechanics list`() {
        assertThatThrownBy {
            Mechanic.Loop(
                targets = Targeter.Caster,
                count = IntValue.Literal(3),
                mechanics = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Choose rejects empty weighted list`() {
        assertThatThrownBy {
            Mechanic.Choose(
                targets = Targeter.Caster,
                weighted = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Choose rejects zero or negative weights`() {
        assertThatThrownBy {
            Mechanic.WeightedBranch(
                weight = 0.0,
                mechanics = listOf(Mechanic.Heal(Targeter.Caster, NumericValue.Literal(1.0))),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
