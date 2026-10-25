package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Author-defined preset for spawning a fresh [PartyIsland] (Wave
 * 5pve-survival / ADR-0107).
 *
 * Templates are immutable value records loaded at server start; the runtime
 * `IPartyIslandManager.create` use case picks one by [id] and stamps the
 * island's geometry from it. The actual block payload lives in the schematic
 * referenced by [schematicPath] — this type only carries the metadata the
 * domain needs to reason about the island shape.
 *
 * `schematicPath` is a relative-or-absolute filesystem path **as a string**
 * (vendor-neutral, mirrors `Schematic.path`). The `ISchematicLoader` adapter
 * wraps it with `java.nio.file.Path.of(...)` at the I/O boundary.
 *
 * `spawnOffset` is the player spawn position within the loaded island world,
 * expressed relative to the schematic's origin (so the template stays valid
 * regardless of where in the world the island is placed).
 *
 * `defaultPlotSize` is the cuboid size (in blocks) handed out by
 * `IIslandPlotManager.assignPlot` when no override is given — a 16×16×64
 * default keeps every party member able to terraform without overlapping.
 */
public data class IslandTemplate(
    val id: IslandTemplateId,
    val schematicPath: String,
    val spawnOffset: Vec3,
    val defaultPlotSize: Vec3,
    val defaultMaxMembers: Int,
) {
    init {
        require(schematicPath.isNotBlank()) {
            "IslandTemplate.schematicPath must not be blank"
        }
        require(defaultMaxMembers in MIN_MEMBERS..MAX_MEMBERS) {
            "IslandTemplate.defaultMaxMembers must be in [$MIN_MEMBERS..$MAX_MEMBERS], was $defaultMaxMembers"
        }
        require(defaultPlotSize.x > 0 && defaultPlotSize.y > 0 && defaultPlotSize.z > 0) {
            "IslandTemplate.defaultPlotSize must be strictly positive on every axis"
        }
    }

    public companion object {
        /** Hard floor — solo island is allowed but discouraged in foundation. */
        public const val MIN_MEMBERS: Int = 1

        /**
         * Hard ceiling — user-confirmed 2026-05-20 ("2-10 人 / 1 instance").
         * Beyond this the per-island chunk load + rss accounting tips over.
         */
        public const val MAX_MEMBERS: Int = 10
    }
}
