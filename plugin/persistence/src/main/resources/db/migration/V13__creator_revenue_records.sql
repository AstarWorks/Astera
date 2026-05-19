-- Wave 5f (ADR-0035, RTM #12) — Creator revenue share per item.
--
-- One row per item; `revenue_share` is a fraction in [0.0, 1.0] applied to
-- the seller's net (after platform fee) at sale time. Single-creator splits
-- only in Phase 1 PoC — multi-creator distribution is Phase 4+.
--
-- `item_id` mirrors `loadouts.item_id` (lower-kebab-case `ItemId.value`,
-- length 64 here matches the kaml-loaded id space — bump if YAML ids grow).
CREATE TABLE creator_revenue_records (
    item_id VARCHAR(64) PRIMARY KEY,
    creator_id UUID NOT NULL,
    revenue_share DOUBLE PRECISION NOT NULL CHECK (revenue_share >= 0.0 AND revenue_share <= 1.0)
);

CREATE INDEX idx_creator_revenue_creator ON creator_revenue_records (creator_id);
