package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.damage.StackHolder
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BoolValue
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** PM-W3: DTO to domain conversion of the combo / stack / dash fields. */
class ComboMechanicYamlTest {

    @Test
    fun `per_stack converts with defaults`() {
        val c = DamageComponentYaml(
            base = NumericValueYaml.Literal(4.0),
            attribute = "physical",
            perStack = PerStackYaml(statusId = "rupture", amount = 1.5),
        ).toDomain()
        val bonus = checkNotNull(c.perStack)
        assertThat(bonus.statusId).isEqualTo(StatusEffectId("rupture"))
        assertThat(bonus.amount).isEqualTo(1.5)
        assertThat(bonus.on).isEqualTo(StackHolder.TARGET)
        assertThat(bonus.consume).isFalse()
    }

    @Test
    fun `has_status takes min_stacks and the on shorthand`() {
        val cond = ConditionYaml.HasStatus(on = StackHolderYaml.CASTER, statusId = "poise", minStacks = 5).toDomain()
        assertThat(cond).isEqualTo(Condition.HasStatus(Targeter.Caster, StatusEffectId("poise"), 5))
        val plain = ConditionYaml.HasStatus(statusId = "poise").toDomain() as Condition.HasStatus
        assertThat(plain.targets).isEqualTo(Targeter.Target)
        assertThat(plain.minStacks).isEqualTo(1)
    }

    @Test
    fun `remove_status stacks default to all`() {
        val all = MechanicYaml.RemoveStatus(TargeterYaml.Target, "rupture").toDomain() as Mechanic.RemoveStatus
        assertThat(all.stacks).isNull()
        val some = MechanicYaml.RemoveStatus(TargeterYaml.Target, "rupture", stacks = 2).toDomain() as Mechanic.RemoveStatus
        assertThat(some.stacks).isEqualTo(IntValue.Literal(2))
    }

    @Test
    fun `dash_strike and hit_stop convert`() {
        val dash = MechanicYaml.DashStrike(
            distance = 6.0,
            ticks = 4,
            onHit = listOf(MechanicYaml.RemoveStatus(TargeterYaml.Target, "tremor")),
        ).toDomain() as Mechanic.DashStrike
        assertThat(dash.distance).isEqualTo(NumericValue.Literal(6.0))
        assertThat(dash.ticks).isEqualTo(IntValue.Literal(4))
        assertThat(dash.radius).isEqualTo(NumericValue.Literal(1.2))
        assertThat(dash.hitOnce).isEqualTo(BoolValue.Literal(true))
        assertThat(dash.invulnerable).isEqualTo(BoolValue.Literal(true))
        assertThat(dash.stopOnBlock).isEqualTo(BoolValue.Literal(true))
        assertThat(dash.followPitch).isEqualTo(BoolValue.Literal(false))
        assertThat(dash.onHit).hasSize(1)

        val stop = MechanicYaml.HitStop(TargeterYaml.Target).toDomain() as Mechanic.HitStop
        assertThat(stop.ticks).isEqualTo(IntValue.Literal(3))
        assertThat(stop.includeCaster).isEqualTo(BoolValue.Literal(false))
    }
}
