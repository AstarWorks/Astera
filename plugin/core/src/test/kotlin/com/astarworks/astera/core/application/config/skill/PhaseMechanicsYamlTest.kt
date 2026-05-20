package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PhaseMechanicsYamlTest {

    @Test
    fun `projectile phases convert to domain projectile`() {
        val dto = PhaseMechanicsYaml.Projectile(
            cast = listOf(
                MechanicYaml.PlaySound(
                    targets = TargeterYaml.Caster,
                    soundId = "skills/plasma-fire",
                ),
            ),
            hitEntity = listOf(
                MechanicYaml.Damage(
                    targets = TargeterYaml.Target,
                    components = listOf(
                        DamageComponentYaml(
                            base = NumericValueYaml.Literal(5.0),
                            attribute = "physical",
                        ),
                    ),
                ),
            ),
            miss = emptyList(),
            expire = listOf(
                MechanicYaml.SpawnParticle(
                    targets = TargeterYaml.ProjectilePosition,
                    particleId = "plasma-dissipate",
                ),
            ),
        )
        val domain = dto.toDomain() as PhaseMechanics.Projectile
        assertThat(domain.cast).hasSize(1)
        assertThat(domain.cast.first()).isInstanceOf(Mechanic.PlaySound::class.java)
        assertThat(domain.hitEntity).hasSize(1)
        assertThat(domain.miss).isEmpty()
        assertThat(domain.expire).hasSize(1)
        assertThat(domain.hitBlock).isEmpty()
    }

    @Test
    fun `melee phases omit hitBlock and expire`() {
        val dto = PhaseMechanicsYaml.Melee(
            cast = listOf(
                MechanicYaml.PlaySound(
                    targets = TargeterYaml.Caster,
                    soundId = "skills/slash",
                ),
            ),
        )
        val domain = dto.toDomain() as PhaseMechanics.Melee
        assertThat(domain.cast).hasSize(1)
        assertThat(domain.hitEntity).isEmpty()
        assertThat(domain.miss).isEmpty()
    }
}
