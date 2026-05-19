package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import com.astarworks.astera.economy.domain.marketplace.MarketplaceListing
import com.astarworks.astera.persistence.table.MarketplaceListingsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IMarketplaceRepository] (Wave 5f / RTM #12 / ADR-0035).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default registered by `AsteraEconomyPlugin`, following the same
 * Wave 3 (ADR-0020) pattern as every other cross-plugin repository.
 *
 * Storage model: one row per listing keyed by [ListingId]; [save] upserts on
 * the primary key so status transitions (Active → Sold / Cancelled / Expired)
 * rewrite the row in place. Status is encoded as the [ListingStatus]
 * discriminator name (`ACTIVE` / `SOLD` / `CANCELLED` / `EXPIRED`); side
 * data for [ListingStatus.Sold] lives in nullable columns (`buyer_id`,
 * `sold_at`). Rows whose status / currency / item-id values fail their
 * domain validators are dropped from reads — same skip-unknown semantics as
 * [PostgresLoadoutRepository].
 */
public class PostgresMarketplaceRepository(private val db: Database) : IMarketplaceRepository {

    override fun findById(id: ListingId): MarketplaceListing? = transaction(db) {
        MarketplaceListingsTable
            .selectAll()
            .where { MarketplaceListingsTable.id eq id.value }
            .firstOrNull()
            ?.toDomainOrNull()
    }

    override fun findActiveBySeller(seller: PlayerId): List<MarketplaceListing> = transaction(db) {
        MarketplaceListingsTable
            .selectAll()
            .where {
                (MarketplaceListingsTable.sellerId eq seller.uuid) and
                    (MarketplaceListingsTable.status eq STATUS_ACTIVE)
            }
            .mapNotNull { it.toDomainOrNull() }
    }

    override fun findAllActive(): List<MarketplaceListing> = transaction(db) {
        MarketplaceListingsTable
            .selectAll()
            .where { MarketplaceListingsTable.status eq STATUS_ACTIVE }
            .mapNotNull { it.toDomainOrNull() }
    }

    override fun save(listing: MarketplaceListing): Unit = transaction(db) {
        MarketplaceListingsTable.upsert(MarketplaceListingsTable.id) {
            it[id] = listing.id.value
            it[sellerId] = listing.seller.uuid
            it[itemId] = listing.itemId.value
            it[priceCurrency] = listing.price.currency.name
            it[priceAmount] = listing.price.amount
            it[listedAt] = listing.listedAt
            it[status] = listing.status.toCode()
            it[buyerId] = (listing.status as? ListingStatus.Sold)?.buyer?.uuid
            it[soldAt] = (listing.status as? ListingStatus.Sold)?.soldAt
            it[cancelledAt] = if (listing.status is ListingStatus.Cancelled) Instant.now() else null
            it[expiredAt] = if (listing.status is ListingStatus.Expired) Instant.now() else null
        }
        Unit
    }

    override fun delete(id: ListingId): Unit = transaction(db) {
        MarketplaceListingsTable.deleteWhere { MarketplaceListingsTable.id eq id.value }
        Unit
    }

    private fun ResultRow.toDomainOrNull(): MarketplaceListing? {
        val itemValue = this[MarketplaceListingsTable.itemId]
        val currencyName = this[MarketplaceListingsTable.priceCurrency]
        val statusCode = this[MarketplaceListingsTable.status]
        val item = runCatching { ItemId(itemValue) }.getOrNull() ?: return null
        val currency = runCatching { Currency.valueOf(currencyName) }.getOrNull() ?: return null
        val status = decodeStatus(
            code = statusCode,
            buyerUuid = this[MarketplaceListingsTable.buyerId],
            soldAt = this[MarketplaceListingsTable.soldAt],
        ) ?: return null
        return MarketplaceListing(
            id = ListingId(this[MarketplaceListingsTable.id]),
            seller = PlayerId(this[MarketplaceListingsTable.sellerId]),
            itemId = item,
            price = ShopPrice(currency = currency, amount = this[MarketplaceListingsTable.priceAmount]),
            listedAt = this[MarketplaceListingsTable.listedAt],
            status = status,
        )
    }

    private fun decodeStatus(
        code: String,
        buyerUuid: java.util.UUID?,
        soldAt: Instant?,
    ): ListingStatus? = when (code) {
        STATUS_ACTIVE -> ListingStatus.Active
        STATUS_SOLD -> if (buyerUuid != null && soldAt != null) {
            ListingStatus.Sold(buyer = PlayerId(buyerUuid), soldAt = soldAt)
        } else {
            // Status SOLD without both side-data columns set is a malformed
            // row; skip rather than fabricate a buyer / timestamp.
            null
        }
        STATUS_CANCELLED -> ListingStatus.Cancelled
        STATUS_EXPIRED -> ListingStatus.Expired
        else -> null
    }

    private fun ListingStatus.toCode(): String = when (this) {
        is ListingStatus.Active -> STATUS_ACTIVE
        is ListingStatus.Sold -> STATUS_SOLD
        is ListingStatus.Cancelled -> STATUS_CANCELLED
        is ListingStatus.Expired -> STATUS_EXPIRED
    }

    private companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_SOLD = "SOLD"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}
