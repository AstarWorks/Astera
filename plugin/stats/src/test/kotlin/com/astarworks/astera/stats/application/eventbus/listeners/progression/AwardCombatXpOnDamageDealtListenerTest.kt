package com.astarworks.astera.stats.application.eventbus.listeners.progression

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.event.SkillXpGained
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillXpCurve
import com.astarworks.astera.stats.application.service.progression.InMemorySkillProgressionRepository
import com.astarworks.astera.stats.application.service.progression.InMemorySkillXpCurveRegistry
import com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class AwardCombatXpOnDamageDealtListenerTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val target = PlayerId(UUID.randomUUID())
    private lateinit var bus: FakeDomainEventBus
    private lateinit var listener: AwardCombatXpOnDamageDealtListener

    @BeforeEach
    fun setUp() {
        val repo = InMemorySkillProgressionRepository()
        val curves = InMemorySkillXpCurveRegistry(defaultCurve = SkillXpCurve.exponential())
        bus = FakeDomainEventBus()
        val useCase = AwardSkillXpUseCase(repo, curves, bus)
        listener = AwardCombatXpOnDamageDealtListener(useCase, xpPerDamage = 1L)
    }

    @Test
    fun `attributed PvP damage awards Combat XP`() {
        val event = damageEvent(attacker = attacker, target = target, amount = 7.5)
        listener.handle(event)

        val gained = bus.publishedOfType<SkillXpGained>()
        assertThat(gained).hasSize(1)
        assertThat(gained.first().kind).isEqualTo(SkillKind.Combat)
        assertThat(gained.first().playerId).isEqualTo(attacker)
        assertThat(gained.first().amount).isEqualTo(8L) // ceil(7.5)
    }

    @Test
    fun `self-damage is ignored`() {
        val event = damageEvent(attacker = attacker, target = attacker, amount = 10.0)
        listener.handle(event)
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `unattributed damage is ignored`() {
        val event = damageEvent(attacker = null, target = target, amount = 10.0)
        listener.handle(event)
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `zero damage is ignored`() {
        val event = damageEvent(attacker = attacker, target = target, amount = 0.0)
        listener.handle(event)
        assertThat(bus.published).isEmpty()
    }

    private fun damageEvent(attacker: PlayerId?, target: PlayerId, amount: Double): DamageDealt {
        val resolved = ResolvedDamage(
            finalAmount = amount,
            attribute = DamageAttribute.PHYSICAL,
            wasBlocked = false,
            wasCritical = false,
        )
        return DamageDealt(
            attacker = attacker,
            target = target,
            resolved = resolved,
            source = DamageSource.Environment.Generic,
            atTick = 0L,
        )
    }
}
