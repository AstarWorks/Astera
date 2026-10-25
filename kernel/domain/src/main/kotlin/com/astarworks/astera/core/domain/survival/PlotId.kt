package com.astarworks.astera.core.domain.survival

import java.util.UUID

/**
 * Identifier for an [IslandPlot] (Wave 5pve-survival / ADR-0107).
 *
 * UUID-based because plots are minted at runtime (the `assignPlot` use case
 * creates one per `(islandId, ownerId)` pair) — there is no author-written
 * stable id to fall back on.
 */
@JvmInline
public value class PlotId(public val value: UUID) {
    override fun toString(): String = value.toString()

    public companion object {
        public fun random(): PlotId = PlotId(UUID.randomUUID())
    }
}
