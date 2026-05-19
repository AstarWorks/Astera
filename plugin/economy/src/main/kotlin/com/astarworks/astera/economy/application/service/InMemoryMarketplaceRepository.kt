package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import com.astarworks.astera.economy.domain.marketplace.MarketplaceListing
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory default for [IMarketplaceRepository]. Wave 5f (ADR-0035) Phase 1
 * fallback when Postgres isn't loaded. `AsteraPersistencePlugin` overlays
 * `PostgresMarketplaceRepository` at priority 100.
 */
public class InMemoryMarketplaceRepository : IMarketplaceRepository {

    private val store = ConcurrentHashMap<ListingId, MarketplaceListing>()

    override fun findById(id: ListingId): MarketplaceListing? = store[id]

    override fun findActiveBySeller(seller: PlayerId): List<MarketplaceListing> =
        store.values.filter { it.seller == seller && it.status is ListingStatus.Active }

    override fun findAllActive(): List<MarketplaceListing> =
        store.values.filter { it.status is ListingStatus.Active }

    override fun save(listing: MarketplaceListing) {
        store[listing.id] = listing
    }

    override fun delete(id: ListingId) {
        store.remove(id)
    }
}
