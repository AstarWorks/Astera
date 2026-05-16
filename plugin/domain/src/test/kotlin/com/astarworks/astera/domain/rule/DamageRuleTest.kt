package com.astarworks.astera.domain.rule

import com.astarworks.astera.domain.model.damage.DamageAttempt
import com.astarworks.astera.domain.model.damage.DamageSource
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.DamageAttribute
import com.astarworks.astera.domain.model.weapon.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DamageRuleTest {

    private val target = PlayerId(UUID.randomUUID())

    private fun attempt(base: Double, headshot: Boolean): DamageAttempt = DamageAttempt(
        targetId = target,
        baseProfile = DamageProfile(base = base, attribute = DamageAttribute.PHYSICAL),
        source = DamageSource.Environment,
        headshot = headshot,
    )

    @Test
    fun `non-headshot returns base`() {
        val out = DamageRule.resolve(attempt(base = 10.0, headshot = false))
        assertThat(out.finalAmount).isEqualTo(10.0)
        assertThat(out.attribute).isEqualTo(DamageAttribute.PHYSICAL)
        assertThat(out.wasCritical).isFalse()
        assertThat(out.wasBlocked).isFalse()
    }

    @Test
    fun `headshot multiplies by headshotMultiplier`() {
        val out = DamageRule.resolve(attempt(base = 10.0, headshot = true), headshotMultiplier = 2.5)
        assertThat(out.finalAmount).isEqualTo(25.0)
        assertThat(out.wasCritical).isTrue()
    }

    @Test
    fun `zero base damage stays zero even on headshot`() {
        val out = DamageRule.resolve(attempt(base = 0.0, headshot = true))
        assertThat(out.finalAmount).isEqualTo(0.0)
        assertThat(out.wasCritical).isFalse()
    }

    @Test
    fun `wasCritical requires headshot and amplified damage`() {
        val noHeadshot = DamageRule.resolve(attempt(base = 10.0, headshot = false))
        assertThat(noHeadshot.wasCritical).isFalse()

        val headshotUnityMultiplier =
            DamageRule.resolve(attempt(base = 10.0, headshot = true), headshotMultiplier = 1.0)
        assertThat(headshotUnityMultiplier.finalAmount).isEqualTo(10.0)
        assertThat(headshotUnityMultiplier.wasCritical).isFalse()
    }
}
