-- Wave 5refine / ADR-0100 §Persistence: per-player ItemInstance storage.
--
-- One row per refined item. `item_id` mirrors content/items/<id>.yaml so
-- analytics + leaderboards can join without cracking JSONB. `layers` JSONB
-- carries the sealed Refinement variant snapshot (Reforge / Enchant /
-- Gemstone / Star / Recomb) and `sub_stat_rolls` JSONB carries the 原神
-- 聖遺物 style sub-stat record list (SubStatKey → magnitude + roll grain
-- count). Both JSONB columns default to '[]' so a never-refined row stays
-- semantically empty without nullability ambiguity.
--
-- `reroll_count` is a queryable column (rather than a JSONB field) so the
-- escalating cost ladder lookup (RefinementMath.rerollCost) does not need a
-- JSONB cast on every withdraw path.
--
-- `idx_item_instances_owner` covers the hot read "all instances owned by
-- player X" used by inventory inspect (Wave 6 GUI) + marketplace listing
-- prerender (ADR-0035 §"refined item flipping").
--
-- Per ADR-0014 JSONB usage policy: queryable filter columns are first-class
-- columns; JSONB only carries variant-shaped payload where the shape itself
-- is the SSoT.
CREATE TABLE item_instances (
    id              UUID PRIMARY KEY,
    item_id         VARCHAR(128) NOT NULL,
    owner_id        UUID NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    layers          JSONB NOT NULL DEFAULT '[]'::jsonb,
    sub_stat_rolls  JSONB NOT NULL DEFAULT '[]'::jsonb,
    reroll_count    INTEGER NOT NULL DEFAULT 0 CHECK (reroll_count >= 0)
);
CREATE INDEX idx_item_instances_owner ON item_instances (owner_id);
CREATE INDEX idx_item_instances_item_id ON item_instances (item_id);
