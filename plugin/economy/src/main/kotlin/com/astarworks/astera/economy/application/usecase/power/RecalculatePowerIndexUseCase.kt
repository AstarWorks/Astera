package com.astarworks.astera.economy.application.usecase.power

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository
import com.astarworks.astera.economy.application.port.outbound.IPlayerInventoryValuator
import com.astarworks.astera.economy.application.port.outbound.IPowerIndexRepository
import com.astarworks.astera.economy.domain.event.PowerIndexComputed
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import com.astarworks.astera.economy.domain.power.PowerIndex
import java.time.Clock

/**
 * Compute and persist a fresh [PowerIndex] snapshot for a single player
 * (Wave 5flex / ADR-0102 surface #4).
 *
 * Idempotent at the snapshot level: re-invoking overwrites the prior
 * snapshot with the freshly-computed values. The Phase C wiring schedules
 * this use case periodically (per ADR-0102 §Mitigations — render time
 * matters, so we recompute on a coarse schedule, not per-tick).
 *
 * # Component sources
 *
 *   - Equipment + Vault: [IPlayerInventoryValuator] (NullInventoryValuator
 *     fallback returns 0 — wallet + listings still contribute).
 *   - Wallet: sum of every [Currency] balance via
 *     [ICurrencyLedger.balance]. Currency weights are *not* applied in
 *     Wave 5flex; future waves may introduce a per-currency multiplier
 *     ([currencyWeight]).
 *   - Marketplace listings: every active listing's price counts as
 *     potential income (HSB Bazaar "buy orders" pattern reference).
 *
 * # Why the event
 *
 * [PowerIndexComputed] lets cross-plugin subscribers react — nameplate
 * adapter refreshes the badge, Web admin updates the dashboard, etc.
 * The event is also a Prometheus signal (Wave _ops-4).
 */
public class RecalculatePowerIndexUseCase(
    private val valuator: IPlayerInventoryValuator,
    private val ledger: ICurrencyLedger,
    private val marketplace: IMarketplaceRepository,
    private val repo: IPowerIndexRepository,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
    private val currencyWeight: (Currency) -> Long = DEFAULT_WEIGHTS,
) {

    public fun recalculate(playerId: PlayerId): PowerIndex {
        val equipment = valuator.estimatedEquipmentValue(playerId)
        val vault = valuator.estimatedVaultValue(playerId)
        val walletTotal = Currency.entries.sumOf { c ->
            val bal = ledger.balance(playerId, c)
            PowerIndex.saturatingAdd(0L, bal * currencyWeight(c).coerceAtLeast(0L))
        }
        val listings = marketplace.findActiveBySeller(playerId)
            .filter { it.status is ListingStatus.Active }
            .sumOf { it.price.amount.coerceAtLeast(0L) }

        val snapshot = PowerIndex(
            playerId = playerId,
            equipmentValue = equipment.coerceAtLeast(0L),
            vaultValue = vault.coerceAtLeast(0L),
            walletValue = walletTotal.coerceAtLeast(0L),
            marketplaceListingValue = listings,
            computedAt = clock.instant(),
        )
        repo.save(snapshot)
        eventBus.publish(
            PowerIndexComputed(
                playerId = playerId,
                total = snapshot.total,
                equipmentValue = snapshot.equipmentValue,
                vaultValue = snapshot.vaultValue,
                walletValue = snapshot.walletValue,
                marketplaceListingValue = snapshot.marketplaceListingValue,
                occurredAt = snapshot.computedAt,
            ),
        )
        return snapshot
    }

    public companion object {
        /**
         * Default per-currency multiplier. GOLD account-bound and tradable (counts as 5).
         */
        public val DEFAULT_WEIGHTS: (Currency) -> Long = { c ->
            when (c) {
                Currency.GOLD -> 5L
            }
        }
    }
}
