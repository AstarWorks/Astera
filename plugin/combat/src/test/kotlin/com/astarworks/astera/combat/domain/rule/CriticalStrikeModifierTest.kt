package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.damage.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CriticalStrikeModifierTest {

    private val target = PlayerId(UUID.randomUUID())

    private fun attempt(base: Double = 10.0): DamageAttempt = DamageAttempt(
        targetId = target,
        baseProfile = DamageProfile.single(base, DamageAttribute.PHYSICAL),
        source = DamageSource.Environment.Generic,
    )

    private fun working(base: Double = 10.0): WorkingDamage =
        WorkingDamage(currentAmount = base, attribute = DamageAttribute.PHYSICAL)

    @Test
    fun `critChance 1_0 always crits and applies multiplier`() {
        val modifier = CriticalStrikeModifier(critChance = 1.0, critMultiplier = 2.0)
        val randomizer = FakeDamageRandomizer(listOf(0.5))

        val out = modifier.apply(working(base = 10.0), attempt(), armor = null, defenderContainer = null, randomizer = randomizer)

        assertThat(out.currentAmount).isEqualTo(20.0)
        assertThat(out.wasCritical).isTrue()
        assertThat(out.criticalMultiplier).isEqualTo(2.0)
    }

    @Test
    fun `critChance 0_0 never crits regardless of roll`() {
        val modifier = CriticalStrikeModifier(critChance = 0.0, critMultiplier = 2.0)
        val randomizer = FakeDamageRandomizer(listOf(0.0))

        val out = modifier.apply(working(base = 10.0), attempt(), armor = null, defenderContainer = null, randomizer = randomizer)

        assertThat(out.currentAmount).isEqualTo(10.0)
        assertThat(out.wasCritical).isFalse()
        assertThat(out.criticalMultiplier).isEqualTo(1.0)
    }

    @Test
    fun `roll below critChance triggers crit`() {
        val modifier = CriticalStrikeModifier(critChance = 0.5, critMultiplier = 3.0)
        val randomizer = FakeDamageRandomizer(listOf(0.0))

        val out = modifier.apply(working(base = 10.0), attempt(), armor = null, defenderContainer = null, randomizer = randomizer)

        assertThat(out.currentAmount).isEqualTo(30.0)
        assertThat(out.wasCritical).isTrue()
        assertThat(out.criticalMultiplier).isEqualTo(3.0)
    }

    @Test
    fun `roll at or above critChance does not crit`() {
        val modifier = CriticalStrikeModifier(critChance = 0.5, critMultiplier = 3.0)
        val randomizer = FakeDamageRandomizer(listOf(0.99))

        val out = modifier.apply(working(base = 10.0), attempt(), armor = null, defenderContainer = null, randomizer = randomizer)

        assertThat(out.currentAmount).isEqualTo(10.0)
        assertThat(out.wasCritical).isFalse()
    }

    @Test
    fun `crit on top of prior headshot stacks multiplicatively`() {
        // Simulate HeadshotModifier already having run: damage 20 (10 * 2x),
        // wasCritical=true with multiplier 2.0.
        val w = WorkingDamage(currentAmount = 20.0, attribute = DamageAttribute.PHYSICAL).also {
            it.markCritical(2.0)
        }
        val modifier = CriticalStrikeModifier(critChance = 1.0, critMultiplier = 2.0)
        val randomizer = FakeDamageRandomizer(listOf(0.0))

        val out = modifier.apply(w, attempt(), armor = null, defenderContainer = null, randomizer = randomizer)

        // Stacked: 10 * 2 (headshot) * 2 (crit) = 40
        assertThat(out.currentAmount).isEqualTo(40.0)
        // Critical signal stays true; markCritical overwrites with the most
        // recent multiplier (acceptable per modifier docstring).
        assertThat(out.wasCritical).isTrue()
        assertThat(out.criticalMultiplier).isEqualTo(2.0)
    }

    @Test
    fun `init rejects invalid critChance`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            CriticalStrikeModifier(critChance = -0.1, critMultiplier = 2.0)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            CriticalStrikeModifier(critChance = 1.1, critMultiplier = 2.0)
        }
    }

    @Test
    fun `init rejects critMultiplier below 1`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            CriticalStrikeModifier(critChance = 0.5, critMultiplier = 0.99)
        }
    }

    @Test
    fun `does not consume randomizer when chance is zero`() {
        val modifier = CriticalStrikeModifier(critChance = 0.0, critMultiplier = 2.0)
        // Counting randomizer: fail if asked for a value.
        val randomizer = object : IDamageRandomizer {
            var called = 0
            override fun nextDouble(): Double {
                called++; return 0.0
            }
        }
        modifier.apply(working(), attempt(), armor = null, defenderContainer = null, randomizer = randomizer)
        assertThat(randomizer.called).isEqualTo(0)
    }
}
