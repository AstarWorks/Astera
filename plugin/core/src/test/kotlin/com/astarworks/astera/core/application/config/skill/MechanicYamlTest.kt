package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.value.BoolValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Round-trip tests for [MechanicYaml] → [Mechanic] conversion. Hammers a
 * representative slice from each thematic category: damage / movement /
 * visual / control-flow.
 */
class MechanicYamlTest {

    @Test
    fun `damage with multi-component compound damage`() {
        val dto = MechanicYaml.Damage(
            targets = TargeterYaml.Target,
            components = listOf(
                DamageComponentYaml(
                    base = NumericValueYaml.Literal(8.0),
                    attribute = "physical",
                ),
                DamageComponentYaml(
                    base = NumericValueYaml.Literal(3.0),
                    attribute = "fire",
                    multiplier = NumericValueYaml.Literal(1.5),
                ),
            ),
            ignoreArmor = BoolValueYaml.Literal(true),
        )
        val domain = dto.toDomain() as Mechanic.Damage
        assertThat(domain.components).hasSize(2)
        assertThat(domain.components[0].attribute).isEqualTo(DamageAttribute.PHYSICAL)
        assertThat(domain.components[1].attribute).isEqualTo(DamageAttribute.FIRE)
        assertThat(domain.ignoreArmor).isEqualTo(BoolValue.Literal(true))
    }

    @Test
    fun `explode carries radius and damage components`() {
        val dto = MechanicYaml.Explode(
            targets = TargeterYaml.Target,
            power = NumericValueYaml.Literal(3.0),
            radius = NumericValueYaml.Literal(5.0),
            components = listOf(DamageComponentYaml(base = NumericValueYaml.Literal(10.0), attribute = "fire")),
        )
        val domain = dto.toDomain() as Mechanic.Explode
        assertThat(domain.radius).isEqualTo(com.astarworks.astera.core.domain.skill.value.NumericValue.Literal(5.0))
        assertThat(domain.components).hasSize(1)
        assertThat(domain.breakBlocks).isEqualTo(BoolValue.Literal(false))
    }

    @Test
    fun `block mechanics default to arena-only and carry the revert delay`() {
        val set = MechanicYaml.SetBlock(
            targets = TargeterYaml.Target,
            blockKey = "minecraft:ice",
            revertAfterTicks = IntValueYaml.Literal(60),
        ).toDomain() as Mechanic.SetBlock
        assertThat(set.outsideArena).isEqualTo(BoolValue.Literal(false))
        assertThat(set.revertAfterTicks).isEqualTo(com.astarworks.astera.core.domain.skill.value.IntValue.Literal(60))

        val remove = MechanicYaml.RemoveBlock(
            targets = TargeterYaml.Target,
            outsideArena = BoolValueYaml.Literal(true),
        ).toDomain() as Mechanic.RemoveBlock
        assertThat(remove.outsideArena).isEqualTo(BoolValue.Literal(true))
        assertThat(remove.revertAfterTicks).isEqualTo(com.astarworks.astera.core.domain.skill.value.IntValue.Literal(0))
    }

    @Test
    fun `knockback with from-hit-normal direction`() {
        val dto = MechanicYaml.Knockback(
            targets = TargeterYaml.Target,
            direction = DirectionRefYaml.FromHitNormal,
            strength = NumericValueYaml.Literal(1.2),
        )
        val domain = dto.toDomain() as Mechanic.Knockback
        assertThat(domain.direction).isSameAs(DirectionRef.FromHitNormal)
    }

    @Test
    fun `spawn particle with timing - per tick trail`() {
        val dto = MechanicYaml.SpawnParticle(
            targets = TargeterYaml.ProjectilePosition,
            particleId = "trail-blue",
            count = IntValueYaml.Literal(4),
            periodTicks = IntValueYaml.Literal(1),
        )
        val domain = dto.toDomain() as Mechanic.SpawnParticle
        assertThat(domain.particleId.value).isEqualTo("trail-blue")
        assertThat(domain.count.toString()).contains("4")
        assertThat((domain.periodTicks as com.astarworks.astera.core.domain.skill.value.IntValue.Literal).value)
            .isEqualTo(1)
    }

    @Test
    fun `if-then with nested then branch`() {
        val dto = MechanicYaml.IfThen(
            predicate = ConditionYaml.HealthBelow(
                targets = TargeterYaml.Target,
                fraction = NumericValueYaml.Literal(0.5),
            ),
            then_ = listOf(
                MechanicYaml.Heal(
                    targets = TargeterYaml.Target,
                    amount = NumericValueYaml.Literal(5.0),
                ),
            ),
        )
        val domain = dto.toDomain() as Mechanic.IfThen
        assertThat(domain.then_).hasSize(1)
        assertThat(domain.then_.first()).isInstanceOf(Mechanic.Heal::class.java)
        assertThat(domain.else_).isEmpty()
    }

    @Test
    fun `cast skill defaults to caster targeter`() {
        val dto = MechanicYaml.CastSkill(skillId = "fireball")
        val domain = dto.toDomain() as Mechanic.CastSkill
        assertThat(domain.skillId.value).isEqualTo("fireball")
        assertThat(domain.targets).isSameAs(com.astarworks.astera.core.domain.skill.targeter.Targeter.Caster)
    }

    @Test
    fun `snapshot_pose and radial directions round-trip`() {
        val dto = MechanicYaml.Knockback(
            targets = TargeterYaml.Target,
            direction = DirectionRefYaml.AwayFromCaster(vertical = 0.3),
            strength = NumericValueYaml.Literal(1.0),
            snapshotPose = true,
        )
        val domain = dto.toDomain() as Mechanic.Knockback
        assertThat(domain.direction).isEqualTo(DirectionRef.AwayFromCaster(0.3))
        assertThat(domain.snapshotPose).isTrue()
        assertThat(DirectionRefYaml.TowardCaster().toDomain()).isEqualTo(DirectionRef.TowardCaster(0.0))
        assertThat(Mechanic.Heal(com.astarworks.astera.core.domain.skill.targeter.Targeter.Caster, com.astarworks.astera.core.domain.skill.value.NumericValue.Literal(1.0)).snapshotPose).isFalse()
    }
}
