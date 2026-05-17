package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RewardOnDamageListenerTest {

    private lateinit var repository: InMemoryPlayerStatsRepository
    private lateinit var listener: RewardOnDamageListener
    private val attackerId = PlayerId(UUID.randomUUID())
    private val targetId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repository = InMemoryPlayerStatsRepository()
        listener = RewardOnDamageListener(repository)
    }

    private fun makeEvent(attacker: PlayerId? = attackerId, dmg: Double = 25.0): DamageDealt =
        DamageDealt(
            attacker = attacker,
            target = targetId,
            resolved = ResolvedDamage(finalAmount = dmg, attribute = DamageAttribute.PHYSICAL, wasBlocked = false, wasCritical = false),
            source = DamageSource.Environment,
        )

    @Test
    fun `handle increases target damageTaken`() {
        listener.handle(makeEvent(dmg = 25.0))
        assertThat(repository.find(targetId)!!.damageTaken).isEqualTo(25L)
    }

    @Test
    fun `handle increases attacker damageDealt`() {
        listener.handle(makeEvent(dmg = 25.0))
        assertThat(repository.find(attackerId)!!.damageDealt).isEqualTo(25L)
    }

    @Test
    fun `handle accumulates damage across events`() {
        listener.handle(makeEvent(dmg = 10.0))
        listener.handle(makeEvent(dmg = 15.0))
        assertThat(repository.find(targetId)!!.damageTaken).isEqualTo(25L)
        assertThat(repository.find(attackerId)!!.damageDealt).isEqualTo(25L)
    }

    @Test
    fun `handle updates target even when attacker is null`() {
        listener.handle(makeEvent(attacker = null, dmg = 30.0))
        assertThat(repository.find(targetId)!!.damageTaken).isEqualTo(30L)
    }

    @Test
    fun `handle does not update attacker when attacker is null`() {
        listener.handle(makeEvent(attacker = null, dmg = 30.0))
        assertThat(repository.find(attackerId)).isNull()
    }
}
