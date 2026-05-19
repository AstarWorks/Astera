package com.astarworks.astera.economy.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.MarketplaceListing

/**
 * Outbound port for player-marketplace listing persistence (RTM #12, ADR-0035).
 *
 * Phase 1 PoC: in-memory default is registered by `AsteraEconomyPlugin`;
 * `AsteraPersistencePlugin` overlays with a Postgres-backed impl at priority
 * 100, following the Wave 3 (ADR-0020) pattern shared by every other
 * cross-plugin repository port.
 *
 * Storage rows are append-mutated by status; the [save] contract upserts on
 * [MarketplaceListing.id] so a status transition (`Active` →
 * `Sold` / `Cancelled` / `Expired`) is expressed as a `copy` + `save` round-trip.
 */
public interface IMarketplaceRepository {

    /** Look up a single listing by id. Returns `null` if no row exists. */
    public fun findById(id: ListingId): MarketplaceListing?

    /**
     * Listings the given player has currently posted and that remain
     * [com.astarworks.astera.economy.domain.marketplace.ListingStatus.Active].
     * Terminal-state rows (sold / cancelled / expired) are excluded — those
     * are pulled via [findById] when needed for audit.
     */
    public fun findActiveBySeller(seller: PlayerId): List<MarketplaceListing>

    /**
     * Every [com.astarworks.astera.economy.domain.marketplace.ListingStatus.Active]
     * listing across all sellers. Wave 5f's browse GUI / `/market` command pull
     * the available offers from this single read.
     */
    public fun findAllActive(): List<MarketplaceListing>

    /** Insert or overwrite a listing keyed by [MarketplaceListing.id]. */
    public fun save(listing: MarketplaceListing)

    /**
     * Hard-delete a listing row. Phase 1 PoC uses this only from tests; the
     * production lifecycle uses status transitions (Cancelled / Expired) so
     * the row remains for audit.
     */
    public fun delete(id: ListingId)
}
