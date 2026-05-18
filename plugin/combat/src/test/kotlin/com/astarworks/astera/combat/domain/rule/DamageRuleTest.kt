package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.domain.weapon.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5a rewrite: `DamageRule` is now a class (pipeline of modifiers) rather
 * than a stateless object. These tests instantiate the rule with
 * `HeadshotModifier` only to preserve the legacy contract semantics.
 *
 * Worker α / β / γ tests cover the additional modifiers (armor / crit /
 * knockback / friendly-fire) in their own files.
 */
class DamageRuleTest {

    private val target = PlayerId(UUID.randomUUID())

    private val nonRandom: IDamageRandomizer = object : IDamageRandomizer {
        override fun nextDouble(): Double = 0.0
    }

    private fun rule(headshotMultiplier: Double = 2.0): DamageRule = DamageRule(
        elementMatrix = IElementMatrix.Neutral,
        modifiers = listOf(HeadshotModifier(multiplier = headshotMultiplier)),
        friendlyFire = FriendlyFirePolicy.Allow,
        randomizer = nonRandom,
    )

    private fun attempt(base: Double, headshot: Boolean): DamageAttempt = DamageAttempt(
        targetId = target,
        baseProfile = DamageProfile(base = base, attribute = DamageAttribute.PHYSICAL),
        source = DamageSource.Environment,
        headshot = headshot,
    )

    @Test
    fun `non-headshot returns base`() {
        val out = rule().resolve(attempt(base = 10.0, headshot = false))
        assertThat(out.resolved.finalAmount).isEqualTo(10.0)
        assertThat(out.resolved.attribute).isEqualTo(DamageAttribute.PHYSICAL)
        assertThat(out.wasCritical).isFalse()
        assertThat(out.resolved.wasBlocked).isFalse()
    }

    @Test
    fun `headshot multiplies by headshotMultiplier`() {
        val out = rule(headshotMultiplier = 2.5).resolve(attempt(base = 10.0, headshot = true))
        assertThat(out.resolved.finalAmount).isEqualTo(25.0)
        assertThat(out.wasCritical).isTrue()
        assertThat(out.criticalMultiplier).isEqualTo(2.5)
    }

    @Test
    fun `zero base damage stays zero even on headshot`() {
        val out = rule().resolve(attempt(base = 0.0, headshot = true))
        assertThat(out.resolved.finalAmount).isEqualTo(0.0)
        assertThat(out.wasCritical).isFalse()
    }

    @Test
    fun `wasCritical requires headshot and amplified damage`() {
        val noHeadshot = rule().resolve(attempt(base = 10.0, headshot = false))
        assertThat(noHeadshot.wasCritical).isFalse()

        val headshotUnityMultiplier = rule(headshotMultiplier = 1.0)
            .resolve(attempt(base = 10.0, headshot = true))
        assertThat(headshotUnityMultiplier.resolved.finalAmount).isEqualTo(10.0)
        assertThat(headshotUnityMultiplier.wasCritical).isFalse()
    }

    @Test
    fun `friendly-fire policy blocks same-team damage`() {
        val ruleWithFF = DamageRule(
            elementMatrix = IElementMatrix.Neutral,
            modifiers = emptyList(),
            friendlyFire = FriendlyFirePolicy.BlockSameTeam,
            randomizer = nonRandom,
        )
        val out = ruleWithFF.resolve(
            attempt = attempt(base = 10.0, headshot = false),
            attackerTeam = com.astarworks.astera.core.domain.match.TeamId("team-a"),
            defenderTeam = com.astarworks.astera.core.domain.match.TeamId("team-a"),
        )
        assertThat(out.resolved.wasBlocked).isTrue()
        assertThat(out.resolved.finalAmount).isEqualTo(0.0)
    }
}
