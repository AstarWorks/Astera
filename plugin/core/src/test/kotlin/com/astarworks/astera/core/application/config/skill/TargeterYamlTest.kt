package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TargeterYamlTest {

    @Test
    fun `caster object`() {
        assertThat(TargeterYaml.Caster.toDomain()).isSameAs(Targeter.Caster)
    }

    @Test
    fun `nearby entities composes correctly`() {
        val dto = TargeterYaml.NearbyEntities(
            center = TargeterYaml.Target,
            radius = NumericValueYaml.Literal(5.0),
            maxCount = IntValueYaml.Literal(10),
            excludeCaster = false,
        )
        val domain = dto.toDomain() as Targeter.NearbyEntities
        assertThat(domain.center).isSameAs(Targeter.Target)
        assertThat(domain.radius).isEqualTo(NumericValue.Literal(5.0))
        assertThat(domain.maxCount).isEqualTo(IntValue.Literal(10))
        assertThat(domain.excludeCaster).isFalse()
    }

    @Test
    fun `filter wraps base with condition`() {
        val dto = TargeterYaml.Filter(
            base = TargeterYaml.NearbyEntities(
                center = TargeterYaml.Caster,
                radius = NumericValueYaml.Literal(8.0),
            ),
            condition = ConditionYaml.HealthBelow(
                targets = TargeterYaml.Target,
                fraction = NumericValueYaml.Literal(0.3),
            ),
        )
        val domain = dto.toDomain() as Targeter.Filter
        assertThat(domain.base).isInstanceOf(Targeter.NearbyEntities::class.java)
        assertThat(domain.condition).isNotNull()
    }
}
