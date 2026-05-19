-- Wave 5f / RTM issue #12 / ADR-0035 — player marketplace listings.
--
-- One row per [MarketplaceListing]. Status is stored as the
-- [ListingStatus] sealed-class discriminator name (ACTIVE / SOLD /
-- CANCELLED / EXPIRED) as TEXT — same forward-compatibility rationale as
-- the `loadouts.slot` column (V5). Terminal-state side data
-- (`buyer_id` / `sold_at` / `cancelled_at` / `expired_at`) sits in
-- nullable columns rather than a JSONB blob because the read patterns
-- (browse active offers, seller dashboard) all filter on the discriminator.
--
-- Phase 1 PoC keeps the row even after a terminal transition so the
-- marketplace history is queryable; status mutations rewrite the row in
-- place via [PostgresMarketplaceRepository.save].
CREATE TABLE marketplace_listings (
    id              UUID PRIMARY KEY,
    seller_id       UUID NOT NULL,
    item_id         VARCHAR(128) NOT NULL,
    price_currency  VARCHAR(16) NOT NULL,
    price_amount    BIGINT NOT NULL CHECK (price_amount > 0),
    listed_at       TIMESTAMP NOT NULL,
    status          VARCHAR(16) NOT NULL,
    -- Sold-state side data: both must be non-null when status = 'SOLD'.
    buyer_id        UUID,
    sold_at         TIMESTAMP,
    -- Terminal-state timestamps for Cancelled / Expired (reserved for the
    -- Phase 1 PoC seller dashboard view).
    cancelled_at    TIMESTAMP,
    expired_at      TIMESTAMP
);

-- Seller dashboard: "show me my listings, separated by status".
CREATE INDEX idx_marketplace_listings_seller_status
    ON marketplace_listings (seller_id, status);

-- Browse path: "newest active listings first".
CREATE INDEX idx_marketplace_listings_status_listed_at
    ON marketplace_listings (status, listed_at);
