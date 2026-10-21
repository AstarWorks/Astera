package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class VanillaKillResolverTest {

    private val resolver = VanillaKillResolver()
    private val victim = PlayerId(UUID.randomUUID())
    private val killer = PlayerId(UUID.randomUUID())

    private fun change(
        old: Double,
        new: Double,
        source: HealthChangeSource = HealthChangeSource.Other("vanilla-entity_attack"),
        tick: Long = 100L,
    ) = PlayerHealthChanged(victim, old, new, source, tick)

    @Test
    fun `lethal vanilla melee publishes kill with killer attribution`() {
        val kill = resolver.resolve(change(3.0, 0.0), PendingVanillaAttribution(victim, killer, 100L))

        assertThat(kill).isNotNull
        assertThat(kill!!.victim).isEqualTo(victim)
        assertThat(kill.killer).isEqualTo(killer)
        assertThat(kill.source).isInstanceOf(DamageSource.Item::class.java)
    }

    @Test
    fun `lethal environment damage has no killer and environment source`() {
        val kill = resolver.resolve(change(3.0, 0.0, HealthChangeSource.Other("vanilla-fall")), null)

        assertThat(kill!!.killer).isNull()
        assertThat(kill.source).isEqualTo(DamageSource.Environment.Generic)
    }

    @Test
    fun `non lethal damage publishes nothing`() {
        assertThat(resolver.resolve(change(10.0, 4.0), null)).isNull()
    }

    @Test
    fun `already dead victim does not re-publish`() {
        assertThat(resolver.resolve(change(0.0, 0.0), null)).isNull()
    }

    @Test
    fun `stale attribution is ignored`() {
        val kill = resolver.resolve(change(3.0, 0.0, tick = 200L), PendingVanillaAttribution(victim, killer, 100L))

        assertThat(kill!!.killer).isNull()
    }

    @Test
    fun `attribution for another victim is ignored`() {
        val other = PendingVanillaAttribution(PlayerId(UUID.randomUUID()), killer, 100L)

        assertThat(resolver.resolve(change(3.0, 0.0), other)!!.killer).isNull()
    }

    @Test
    fun `heals and respawn writes never count as kills`() {
        assertThat(resolver.resolve(change(0.0, 20.0, HealthChangeSource.Respawn), null)).isNull()
        assertThat(resolver.resolve(change(3.0, 0.0, HealthChangeSource.Regen), null)).isNull()
        assertThat(resolver.resolve(change(3.0, 0.0, HealthChangeSource.Other("defensive-revive")), null)).isNull()
    }

    @Test
    fun `status tick kill maps to status effect source`() {
        val kill = resolver.resolve(change(1.0, 0.0, HealthChangeSource.Status("burn")), null)

        assertThat(kill!!.source).isInstanceOf(DamageSource.StatusEffectTick::class.java)
    }
}
