package com.astarworks.astera.persistence.table.survival

import com.astarworks.astera.persistence.jsonb.IslandPlotAllowedJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * `island_plots` table (Wave 5pve-survival / ADR-0107, V41 migration).
 *
 * One row per [com.astarworks.astera.core.domain.survival.IslandPlot]. The
 * cuboid bounds are stored as 6 flat columns so position-to-plot lookups
 * can use a B-tree range index (vs JSONB requiring a SQL function call
 * per row).
 */
public object IslandPlotsTable : Table("island_plots") {
    public val plotId = javaUUID("plot_id")
    public val islandId = varchar("island_id", 64)
    public val ownerId = javaUUID("owner_id")
    public val minX = double("plot_min_x")
    public val minY = double("plot_min_y")
    public val minZ = double("plot_min_z")
    public val maxX = double("plot_max_x")
    public val maxY = double("plot_max_y")
    public val maxZ = double("plot_max_z")
    public val buildAllowed = jsonb("build_allowed", IslandPlotAllowedJson.serializer())

    override val primaryKey: PrimaryKey = PrimaryKey(plotId)
}
