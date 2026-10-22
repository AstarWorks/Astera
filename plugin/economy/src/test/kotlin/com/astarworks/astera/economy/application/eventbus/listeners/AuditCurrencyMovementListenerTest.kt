package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AuditCurrencyMovementListenerTest {

    private val player = PlayerId(UUID.randomUUID())

    @Test
    fun `a shop purchase is logged with signed delta, balance and a greppable reason`() {
        val repo = InMemoryAuditLogRepository()
        val listener = AuditCurrencyMovementListener { repo }

        listener.handle(
            CurrencyChanged(
                player, Currency.STAR, -120L, 30L,
                CurrencyChangeReason.ShopPurchase(ShopId("example-shop"), ShopOfferId("pistol")),
            ),
        )

        val row = repo.recent(limit = 10).single() as AuditEvent.CurrencyMovement
        assertThat(row.player).isEqualTo(player)
        assertThat(row.currency).isEqualTo(Currency.STAR)
        assertThat(row.delta).isEqualTo(-120L)
        assertThat(row.newBalance).isEqualTo(30L)
        assertThat(row.reason).isEqualTo("shop:example-shop/pistol")
    }

    @Test
    fun `zero deltas and a missing audit store are ignored without throwing`() {
        val repo = InMemoryAuditLogRepository()
        AuditCurrencyMovementListener { repo }.handle(CurrencyChanged(player, Currency.GOLD, 0L, 5L, CurrencyChangeReason.Deposit))
        assertThat(repo.recent(limit = 10)).isEmpty()

        AuditCurrencyMovementListener { null }.handle(CurrencyChanged(player, Currency.GOLD, 5L, 5L, CurrencyChangeReason.Deposit))
    }

    @Test
    fun `every reason has a stable description`() {
        val reasons = listOf(
            CurrencyChangeReason.Deposit, CurrencyChangeReason.Withdraw, CurrencyChangeReason.MatchReward,
            CurrencyChangeReason.MissionReward, CurrencyChangeReason.AdminGrant, CurrencyChangeReason.TutorialReward,
            CurrencyChangeReason.Transfer(player), CurrencyChangeReason.Betting(UUID.randomUUID(), "payout"),
        )
        assertThat(reasons.map { AuditCurrencyMovementListener.describe(it) }).doesNotHaveDuplicates().allMatch { it.isNotBlank() }
    }
}
