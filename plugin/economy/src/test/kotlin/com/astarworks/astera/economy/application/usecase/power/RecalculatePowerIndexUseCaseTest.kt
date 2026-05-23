package com.astarworks.astera.economy.application.usecase.power

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.economy.application.port.outbound.NullInventoryValuator
import com.astarworks.astera.economy.application.port.outbound.IPlayerInventoryValuator
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.service.InMemoryMarketplaceRepository
import com.astarworks.astera.economy.application.service.power.InMemoryPowerIndexRepository
import com.astarworks.astera.economy.domain.event.PowerIndexComputed
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import com.astarworks.astera.economy.domain.marketplace.MarketplaceListing
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RecalculatePowerIndexUseCaseTest {

    private val player = PlayerId(UUID.randomUUID())
    private val now = Instant.parse("2026-05-20T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun newUseCase(
        valuator: IPlayerInventoryValuator = NullInventoryValuator(),
        ledger: InMemoryCurrencyLedger = InMemoryCurrencyLedger(),
        market: InMemoryMarketplaceRepository = InMemoryMarketplaceRepository(),
        repo: InMemoryPowerIndexRepository = InMemoryPowerIndexRepository(),
        eventBus: FakeDomainEventBus = FakeDomainEventBus(),
    ): Quintet = Quintet(
        uc = RecalculatePowerIndexUseCase(
            valuator = valuator,
            ledger = ledger,
            marketplace = market,
            repo = repo,
            eventBus = eventBus,
            clock = clock,
        ),
        ledger = ledger,
        market = market,
        repo = repo,
        eventBus = eventBus,
    )

    private data class Quintet(
        val uc: RecalculatePowerIndexUseCase,
        val ledger: InMemoryCurrencyLedger,
        val market: InMemoryMarketplaceRepository,
        val repo: InMemoryPowerIndexRepository,
        val eventBus: FakeDomainEventBus,
    )

    @Test
    fun `fresh player gets an empty snapshot`() {
        val ctx = newUseCase()
        val snap = ctx.uc.recalculate(player)
        assertThat(snap.total).isZero()
        assertThat(snap.computedAt).isEqualTo(now)
        assertThat(ctx.repo.find(player)).isEqualTo(snap)
    }

    @Test
    fun `wallet contributes scaled by default weights`() {
        val ctx = newUseCase()
        ctx.ledger.credit(player, Currency.STAR, 100L)   // *1 = 100
        ctx.ledger.credit(player, Currency.GOLD, 10L)    // *5 = 50
        ctx.ledger.credit(player, Currency.GEM, 1L)      // *100 = 100
        val snap = ctx.uc.recalculate(player)
        assertThat(snap.walletValue).isEqualTo(250L)
        assertThat(snap.total).isEqualTo(250L)
    }

    @Test
    fun `marketplace active listings count toward listing value`() {
        val ctx = newUseCase()
        val listing1 = MarketplaceListing(
            id = ListingId(UUID.randomUUID()),
            seller = player,
            itemId = ItemId("sword"),
            price = ShopPrice(Currency.GOLD, 500L),
            listedAt = now,
            status = ListingStatus.Active,
        )
        val listing2 = listing1.copy(id = ListingId(UUID.randomUUID()), price = ShopPrice(Currency.GOLD, 1000L))
        ctx.market.save(listing1)
        ctx.market.save(listing2)
        val snap = ctx.uc.recalculate(player)
        assertThat(snap.marketplaceListingValue).isEqualTo(1500L)
        assertThat(snap.total).isEqualTo(1500L)
    }

    @Test
    fun `valuator equipment and vault values are included`() {
        val valuator = object : IPlayerInventoryValuator {
            override fun estimatedEquipmentValue(playerId: PlayerId): Long = 7000L
            override fun estimatedVaultValue(playerId: PlayerId): Long = 2000L
        }
        val ctx = newUseCase(valuator = valuator)
        val snap = ctx.uc.recalculate(player)
        assertThat(snap.equipmentValue).isEqualTo(7000L)
        assertThat(snap.vaultValue).isEqualTo(2000L)
        assertThat(snap.total).isEqualTo(9000L)
    }

    @Test
    fun `event published with full breakdown`() {
        val valuator = object : IPlayerInventoryValuator {
            override fun estimatedEquipmentValue(playerId: PlayerId): Long = 5L
            override fun estimatedVaultValue(playerId: PlayerId): Long = 6L
        }
        val ctx = newUseCase(valuator = valuator)
        ctx.ledger.credit(player, Currency.STAR, 7L)
        ctx.uc.recalculate(player)
        val events = ctx.eventBus.publishedOfType<PowerIndexComputed>()
        assertThat(events).hasSize(1)
        val ev = events.single()
        assertThat(ev.playerId).isEqualTo(player)
        assertThat(ev.equipmentValue).isEqualTo(5L)
        assertThat(ev.vaultValue).isEqualTo(6L)
        assertThat(ev.walletValue).isEqualTo(7L)
        assertThat(ev.marketplaceListingValue).isZero()
        assertThat(ev.total).isEqualTo(18L)
        assertThat(ev.occurredAt).isEqualTo(now)
    }

    @Test
    fun `recalculate overwrites prior snapshot`() {
        val ctx = newUseCase()
        ctx.ledger.credit(player, Currency.STAR, 100L)
        val s1 = ctx.uc.recalculate(player)
        ctx.ledger.credit(player, Currency.STAR, 50L)
        val s2 = ctx.uc.recalculate(player)
        assertThat(s2.walletValue).isGreaterThan(s1.walletValue)
        assertThat(ctx.repo.find(player)?.walletValue).isEqualTo(s2.walletValue)
    }
}
