package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PhaseOverridesYamlTest {

    @Test
    fun `phase overrides DTO converts to domain with non-null replace`() {
        val dto = PhaseOverridesYaml(
            hitEntityAppend = listOf(
                MechanicYaml.Ignite(
                    targets = TargeterYaml.Target,
                    ticks = IntValueYaml.Literal(60),
                ),
            ),
        )
        val domain = dto.toDomain()
        assertThat(domain.hitEntityAppend).hasSize(1)
        assertThat(domain.hitEntityAppend.first()).isInstanceOf(Mechanic.Ignite::class.java)
        assertThat(domain.hitEntityReplace).isNull()
    }

    @Test
    fun `applyTo on projectile composes prepend+base+append`() {
        val baseHit = MechanicYaml.Damage(
            targets = TargeterYaml.Target,
            components = listOf(
                DamageComponentYaml(
                    base = NumericValueYaml.Literal(5.0),
                    attribute = "physical",
                ),
            ),
        ).toDomain()
        val base = PhaseMechanics.Projectile(hitEntity = listOf(baseHit))
        val overrides = PhaseOverridesYaml(
            hitEntityAppend = listOf(
                MechanicYaml.Ignite(targets = TargeterYaml.Target, ticks = IntValueYaml.Literal(40)),
            ),
            hitEntityPrepend = listOf(
                MechanicYaml.PlaySound(targets = TargeterYaml.Caster, soundId = "skills/hit"),
            ),
        ).toDomain()
        val applied = overrides.applyTo(base) as PhaseMechanics.Projectile
        assertThat(applied.hitEntity).hasSize(3)
        assertThat(applied.hitEntity[0]).isInstanceOf(Mechanic.PlaySound::class.java)
        assertThat(applied.hitEntity[1]).isInstanceOf(Mechanic.Damage::class.java)
        assertThat(applied.hitEntity[2]).isInstanceOf(Mechanic.Ignite::class.java)
    }

    @Test
    fun `applyTo replace discards the base list`() {
        val base = PhaseMechanics.Projectile(
            hitEntity = listOf(
                MechanicYaml.Damage(
                    targets = TargeterYaml.Target,
                    components = listOf(
                        DamageComponentYaml(
                            base = NumericValueYaml.Literal(5.0),
                            attribute = "physical",
                        ),
                    ),
                ).toDomain(),
            ),
        )
        val overrides = PhaseOverridesYaml(
            hitEntityReplace = listOf(
                MechanicYaml.Heal(targets = TargeterYaml.Caster, amount = NumericValueYaml.Literal(2.0)),
            ),
        ).toDomain()
        val applied = overrides.applyTo(base) as PhaseMechanics.Projectile
        assertThat(applied.hitEntity).hasSize(1)
        assertThat(applied.hitEntity.first()).isInstanceOf(Mechanic.Heal::class.java)
    }

    @Test
    fun `applyTo on melee ignores projectile-only phases`() {
        val base = PhaseMechanics.Melee()
        val overrides = PhaseOverridesYaml(
            hitBlockAppend = listOf(
                MechanicYaml.Ignite(targets = TargeterYaml.Target, ticks = IntValueYaml.Literal(20)),
            ),
            castAppend = listOf(
                MechanicYaml.PlaySound(targets = TargeterYaml.Caster, soundId = "skills/swing"),
            ),
        ).toDomain()
        val applied = overrides.applyTo(base) as PhaseMechanics.Melee
        assertThat(applied.cast).hasSize(1)
        // melee has no hitBlock; the projectile-specific override is silently ignored
        assertThat(applied.hitEntity).isEmpty()
    }
}
