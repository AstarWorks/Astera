package com.astarworks.astera.combat.application.eventbus.listeners.ego

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.service.InMemoryEgoGauge
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.ego.EgoConfig
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class EgoGaugeCombatListenerTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())
    private val gauge = InMemoryEgoGauge(FakeScheduler(), FakeDomainEventBus(), EgoConfig(gainOnDealt = 5, gainOnTaken = 3))
    private val listener = EgoGaugeCombatListener(gauge)

    private fun hit(from: PlayerId?, to: PlayerId, amount: Double = 6.0, blocked: Boolean = false) = DamageDealt(
        attacker = from,
        target = to,
        resolved = ResolvedDamage(amount, DamageAttribute.PHYSICAL, wasBlocked = blocked, wasCritical = false),
        source = DamageSource.Environment.Generic,
        atTick = 1L,
    )

    @Test
    fun `a hit fills the attacker by gain_on_dealt and the victim by gain_on_taken`() {
        listener.onDamage(hit(attacker, victim))
        listener.onDamage(hit(attacker, victim))

        assertThat(gauge.value(attacker)).isEqualTo(10)
        assertThat(gauge.value(victim)).isEqualTo(6)
    }

    @Test
    fun `blocked, zero and self hits give nothing`() {
        listener.onDamage(hit(attacker, victim, blocked = true))
        listener.onDamage(hit(attacker, victim, amount = 0.0))
        listener.onDamage(hit(attacker, attacker))

        assertThat(gauge.value(attacker)).isZero()
        assertThat(gauge.value(victim)).isZero()
    }

    @Test
    fun `environmental damage only fills the victim`() {
        listener.onDamage(hit(null, victim))

        assertThat(gauge.value(victim)).isEqualTo(3)
    }

    @Test
    fun `death empties the victim`() {
        listener.onDamage(hit(attacker, victim))
        listener.onKilled(PlayerKilled(victim, attacker, DamageSource.Environment.Generic, atTick = 2L))

        assertThat(gauge.value(victim)).isZero()
        assertThat(gauge.value(attacker)).isEqualTo(5)
    }
}
