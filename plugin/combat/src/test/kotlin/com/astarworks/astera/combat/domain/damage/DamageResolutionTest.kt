package com.astarworks.astera.combat.domain.damage

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.domain.weapon.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DamageResolutionTest {

    private fun attempt(base: Double = 10.0, attr: DamageAttribute = DamageAttribute.PHYSICAL) = DamageAttempt(
        targetId = PlayerId(UUID.randomUUID()),
        baseProfile = DamageProfile(base = base, attribute = attr),
        source = DamageSource.Environment,
    )

    @Test
    fun `blocked sets wasBlocked true and finalAmount zero`() {
        val res = DamageResolution.blocked(attempt(base = 25.0))

        assertThat(res.resolved.wasBlocked).isTrue()
        assertThat(res.resolved.finalAmount).isEqualTo(0.0)
        assertThat(res.resolved.wasCritical).isFalse()
        assertThat(res.mitigations).isEmpty()
        assertThat(res.knockback).isNull()
        assertThat(res.wasCritical).isFalse()
        assertThat(res.criticalMultiplier).isEqualTo(1.0)
    }

    @Test
    fun `blocked preserves damage attribute from attempt`() {
        val res = DamageResolution.blocked(attempt(attr = DamageAttribute.FIRE))
        assertThat(res.resolved.attribute).isEqualTo(DamageAttribute.FIRE)
    }

    @Test
    fun `of builds baseline non-critical resolution`() {
        val res = DamageResolution.of(finalAmount = 12.5, attribute = DamageAttribute.PHYSICAL)

        assertThat(res.resolved.finalAmount).isEqualTo(12.5)
        assertThat(res.resolved.attribute).isEqualTo(DamageAttribute.PHYSICAL)
        assertThat(res.resolved.wasBlocked).isFalse()
        assertThat(res.resolved.wasCritical).isFalse()
        assertThat(res.wasCritical).isFalse()
        assertThat(res.criticalMultiplier).isEqualTo(1.0)
        assertThat(res.mitigations).isEmpty()
        assertThat(res.knockback).isNull()
    }

    @Test
    fun `of with wasCritical true sets default multiplier 2_0`() {
        val res = DamageResolution.of(finalAmount = 20.0, attribute = DamageAttribute.PHYSICAL, wasCritical = true)

        assertThat(res.wasCritical).isTrue()
        assertThat(res.resolved.wasCritical).isTrue()
        assertThat(res.criticalMultiplier).isEqualTo(2.0)
    }

    @Test
    fun `direct construction carries all rich fields`() {
        val mitigation = MitigationApplied(
            reason = MitigationReason.Resist(DamageAttribute.FIRE, percent = 0.25),
            amountReduced = 5.0,
        )
        val impulse = Vec3(-0.4, 0.0, 0.0)
        val res = DamageResolution(
            resolved = ResolvedDamage(
                finalAmount = 15.0,
                attribute = DamageAttribute.FIRE,
                wasBlocked = false,
                wasCritical = true,
            ),
            mitigations = listOf(mitigation),
            knockback = impulse,
            wasCritical = true,
            criticalMultiplier = 3.0,
        )

        assertThat(res.resolved.finalAmount).isEqualTo(15.0)
        assertThat(res.mitigations).containsExactly(mitigation)
        assertThat(res.knockback).isEqualTo(impulse)
        assertThat(res.criticalMultiplier).isEqualTo(3.0)
    }
}
