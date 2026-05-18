package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ProjectileOrigin
import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.domain.projectile.ProjectileId
import com.astarworks.astera.weapons.domain.weapon.DamageProfile
import com.astarworks.astera.weapons.domain.weapon.WeaponId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.util.UUID

class KnockbackModifierTest {

    private val target = PlayerId(UUID.randomUUID())
    private val attacker = PlayerId(UUID.randomUUID())

    /** Randomizer that fails the test if consulted — KnockbackModifier must not roll. */
    private val unusedRandomizer: IDamageRandomizer = object : IDamageRandomizer {
        override fun nextDouble(): Double = error("KnockbackModifier must not consult the randomizer")
    }

    private fun attempt(source: DamageSource, base: Double = 10.0): DamageAttempt = DamageAttempt(
        targetId = target,
        baseProfile = DamageProfile(base = base, attribute = DamageAttribute.PHYSICAL),
        source = source,
    )

    private fun working(base: Double = 10.0): WorkingDamage =
        WorkingDamage(currentAmount = base, attribute = DamageAttribute.PHYSICAL)

    @Test
    fun `Weapon source with attackerPosition produces inverse-direction impulse`() {
        val source = DamageSource.Weapon(
            weaponId = WeaponId("test-sword"),
            attackerId = attacker,
            attackerPosition = Vec3(1.0, 0.0, 0.0),
        )
        val modifier = KnockbackModifier(baseMagnitude = 0.4)

        val out = modifier.apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)

        val impulse = out.knockback
        assertThat(impulse).isNotNull
        impulse!!
        assertThat(impulse.x).isEqualTo(-0.4)
        assertThat(impulse.y).isEqualTo(0.0)
        assertThat(impulse.z).isEqualTo(0.0)
    }

    @Test
    fun `Weapon source with null attackerPosition produces no knockback`() {
        val source = DamageSource.Weapon(
            weaponId = WeaponId("test-sword"),
            attackerId = attacker,
            attackerPosition = null,
        )
        val modifier = KnockbackModifier(baseMagnitude = 0.4)

        val out = modifier.apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)

        assertThat(out.knockback).isNull()
    }

    @Test
    fun `Skill source with casterPosition produces inverse-direction impulse`() {
        val source = DamageSource.Skill(
            skillId = SkillId("fireball"),
            casterId = attacker,
            casterPosition = Vec3(0.0, 0.0, 2.0),
        )
        val modifier = KnockbackModifier(baseMagnitude = 0.5)

        val out = modifier.apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)

        val impulse = out.knockback!!
        assertThat(impulse.x).isEqualTo(0.0)
        assertThat(impulse.y).isEqualTo(0.0)
        assertThat(impulse.z).isEqualTo(-0.5)
    }

    @Test
    fun `Skill source with null casterPosition produces no knockback`() {
        val source = DamageSource.Skill(
            skillId = SkillId("fireball"),
            casterId = attacker,
            casterPosition = null,
        )
        val modifier = KnockbackModifier()

        val out = modifier.apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)

        assertThat(out.knockback).isNull()
    }

    @Test
    fun `Explosion source produces impulse away from center`() {
        val source = DamageSource.Explosion(center = Vec3(2.0, 0.0, 0.0), radius = 4.0)
        val modifier = KnockbackModifier(baseMagnitude = 0.4)

        val out = modifier.apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)

        val impulse = out.knockback!!
        // direction = -normalize((2,0,0)) * 0.4 = (-0.4, 0, 0)
        assertThat(impulse.x).isEqualTo(-0.4)
        assertThat(impulse.y).isEqualTo(0.0)
        assertThat(impulse.z).isEqualTo(0.0)
    }

    @Test
    fun `Environment source produces no knockback`() {
        val modifier = KnockbackModifier()
        val out = modifier.apply(working(), attempt(DamageSource.Environment), armor = null, randomizer = unusedRandomizer)
        assertThat(out.knockback).isNull()
    }

    @Test
    fun `Fall source produces no knockback`() {
        val modifier = KnockbackModifier()
        val out = modifier.apply(working(), attempt(DamageSource.Fall(distance = 6.0)), armor = null, randomizer = unusedRandomizer)
        assertThat(out.knockback).isNull()
    }

    @Test
    fun `StatusEffectTick source produces no knockback`() {
        val source = DamageSource.StatusEffectTick(StatusEffectId("burn"), originPlayerId = attacker)
        val out = KnockbackModifier().apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)
        assertThat(out.knockback).isNull()
    }

    @Test
    fun `Projectile source produces no knockback in Wave 5a`() {
        // Wave 6 will extend Projectile with an impact position; for now no knockback.
        val source = DamageSource.Projectile(
            projectileId = ProjectileId(UUID.randomUUID()),
            ownerId = attacker,
            origin = ProjectileOrigin.FromWeapon(WeaponId("pistol")),
        )
        val out = KnockbackModifier().apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)
        assertThat(out.knockback).isNull()
    }

    @Test
    fun `attackerPosition at ZERO yields zero-vector impulse`() {
        val source = DamageSource.Weapon(
            weaponId = WeaponId("test"),
            attackerId = attacker,
            attackerPosition = Vec3.ZERO,
        )
        val modifier = KnockbackModifier(baseMagnitude = 0.4)

        val out = modifier.apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)

        // Vec3.ZERO.normalize() == ZERO; ZERO * -0.4 == ZERO. Knockback field
        // is set but vector magnitude is 0 (gateway treats as no-op).
        val impulse = out.knockback!!
        assertThat(impulse.length()).isEqualTo(0.0, within(1e-9))
    }

    @Test
    fun `diagonal attackerPosition is normalized to unit length`() {
        val source = DamageSource.Weapon(
            weaponId = WeaponId("test"),
            attackerId = attacker,
            attackerPosition = Vec3(3.0, 0.0, 4.0), // length 5
        )
        val modifier = KnockbackModifier(baseMagnitude = 1.0)

        val out = modifier.apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)

        val impulse = out.knockback!!
        // direction = -normalize((3,0,4)) = -(0.6, 0, 0.8) = (-0.6, 0, -0.8); magnitude 1.0
        assertThat(impulse.x).isEqualTo(-0.6, within(1e-9))
        assertThat(impulse.y).isEqualTo(0.0)
        assertThat(impulse.z).isEqualTo(-0.8, within(1e-9))
        assertThat(impulse.length()).isEqualTo(1.0, within(1e-9))
    }

    @Test
    fun `baseMagnitude zero short-circuits with no knockback`() {
        val source = DamageSource.Weapon(
            weaponId = WeaponId("test"),
            attackerId = attacker,
            attackerPosition = Vec3(1.0, 0.0, 0.0),
        )
        val modifier = KnockbackModifier(baseMagnitude = 0.0)

        val out = modifier.apply(working(), attempt(source), armor = null, randomizer = unusedRandomizer)

        assertThat(out.knockback).isNull()
    }

    @Test
    fun `init rejects negative baseMagnitude`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            KnockbackModifier(baseMagnitude = -0.1)
        }
    }
}
