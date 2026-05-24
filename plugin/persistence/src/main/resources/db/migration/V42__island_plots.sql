-- Wave 5pve-survival / ADR-0107 §Persistence: per-member plot allocation.
--
-- One row per [com.astarworks.astera.core.domain.survival.IslandPlot].
--
-- # FK to party_islands
--
-- ON DELETE CASCADE so removing an island drops its plots. Plots without
-- an island would be orphan data — every plot reads through the parent
-- island id, so cascade is the natural cleanup.
--
-- # Region bounds as 6 columns
--
-- Region is `(Vec3 min, Vec3 max)` = 6 doubles. Storing flat columns
-- keeps the cuboid filter (`WHERE plot_min_x <= ? AND plot_max_x >= ?`)
-- index-friendly for the position-to-plot lookup in
-- `IIslandPlotManager.canBuild`. JSONB would force a SQL function call
-- per row.
--
-- # build_allowed as JSONB array
--
-- The allow-list is read whole-or-not-at-all (the `canBuild` decision
-- needs the full set; no partial lookup). JSONB array fits ADR-0014's
-- "read-whole aggregate" rule. Owner is denormalised as `owner_id`
-- for the `forOwner(islandId, ownerId)` lookup.
CREATE TABLE island_plots (
    plot_id            UUID PRIMARY KEY,
    island_id          VARCHAR(64) NOT NULL
                              REFERENCES party_islands (island_id) ON DELETE CASCADE,
    owner_id           UUID NOT NULL,
    plot_min_x         DOUBLE PRECISION NOT NULL,
    plot_min_y         DOUBLE PRECISION NOT NULL,
    plot_min_z         DOUBLE PRECISION NOT NULL,
    plot_max_x         DOUBLE PRECISION NOT NULL,
    plot_max_y         DOUBLE PRECISION NOT NULL,
    plot_max_z         DOUBLE PRECISION NOT NULL,
    build_allowed      JSONB NOT NULL,
    CONSTRAINT island_plots_bounds_ordered CHECK (
        plot_min_x <= plot_max_x AND
        plot_min_y <= plot_max_y AND
        plot_min_z <= plot_max_z
    )
);

-- # Indexes
--
-- One plot per (island, owner) in foundation (relaxable for multi-plot
-- Phase 4). Island index supports plotsOn(islandId) listing.
CREATE UNIQUE INDEX idx_island_plots_owner_unique
    ON island_plots (island_id, owner_id);
CREATE INDEX idx_island_plots_island
    ON island_plots (island_id);
