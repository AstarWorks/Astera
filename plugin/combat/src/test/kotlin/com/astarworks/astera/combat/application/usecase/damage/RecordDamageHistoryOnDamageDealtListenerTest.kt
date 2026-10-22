package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.combat.application.service.InMemoryDamageHistoryService
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordDamageHistoryOnDamageDealtListenerTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val target = PlayerId(UUID.randomUUID())

    private fun event(attacker: PlayerId?, amount: Double = 7.0, atTick: Long = 50L, context: CombatContext = CombatContext.Match()) = DamageDealt(
        attacker = attacker,
        target = target,
        resolved = ResolvedDamage(
            finalAmount = amount,
            attribute = DamageAttribute.PHYSICAL,
            wasBlocked = false,
            wasCritical = false,
        ),
        source = DamageSource.Environment.Generic,
        atTick = atTick,
        context = context,
    )

    @Test
    fun `training and lobby hits leave no assist trail (ADR-0237)`() {
        val svc = InMemoryDamageHistoryService()
        val listener = RecordDamageHistoryOnDamageDealtListener(svc)
        for (context in listOf(CombatContext.Training, CombatContext.Lobby, CombatContext.World)) {
            listener.handle(event(attacker = attacker, context = context))
        }
        assertThat(svc.entries(target)).isEmpty()
    }

    @Test
    fun `attacker null skips record`() {
        val svc = InMemoryDamageHistoryService()
        val listener = RecordDamageHistoryOnDamageDealtListener(svc)
        listener.handle(event(attacker = null))
        assertThat(svc.entries(target)).isEmpty()
    }

    @Test
    fun `attacker present records into history`() {
        val svc = InMemoryDamageHistoryService()
        val listener = RecordDamageHistoryOnDamageDealtListener(svc)
        listener.handle(event(attacker = attacker, amount = 12.0, atTick = 77L))
        val entries = svc.entries(target)
        assertThat(entries).hasSize(1)
        assertThat(entries.single().attackerId).isEqualTo(attacker)
        assertThat(entries.single().amount).isEqualTo(12.0)
        assertThat(entries.single().at).isEqualTo(77L)
    }
}
