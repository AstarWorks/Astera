package com.astarworks.astera.core.application.service.portal

import com.astarworks.astera.core.application.port.outbound.portal.IPortalGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.portal.Portal
import com.astarworks.astera.core.domain.portal.PortalId
import com.astarworks.astera.core.domain.portal.PortalShape
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Default in-memory [IPortalGateway] (Wave 5block-embed, ADR-0120).
 *
 * Holds two structures:
 *
 *  - [byId] — `PortalId` → [Portal] for lifecycle ops.
 *  - [byBlockPosition] — `(world, position) → Portal` for O(1) lookup of
 *    block-shape portals.
 *
 * Region-shape portals are stored only in [byId]; [findByPosition] walks
 * the per-world filtered subset (typically 0–10 entries) for AABB hit-
 * tests. The dual structure beats a single linear scan when most portals
 * are block-shape (the expected mix per the Phase 1 PoC stages).
 */
public class InMemoryPortalGateway : IPortalGateway {

    private val byId: MutableMap<PortalId, Portal> = mutableMapOf()
    private val byBlockPosition: MutableMap<BlockKey, Portal> = mutableMapOf()

    private data class BlockKey(val world: WorldId, val position: Vec3)

    override fun register(portal: Portal): Portal? {
        val prior = byId[portal.id]
        if (prior != null) {
            // Drop prior block index entry so re-register doesn't leave stale
            // (world, position) → priorPortal mapping behind.
            removeBlockIndex(prior)
        }
        byId[portal.id] = portal
        if (portal.sourceShape is PortalShape.Block) {
            byBlockPosition[BlockKey(portal.sourceWorld, portal.sourceShape.position)] = portal
        }
        return prior
    }

    override fun unregister(id: PortalId): Boolean {
        val removed = byId.remove(id) ?: return false
        removeBlockIndex(removed)
        return true
    }

    private fun removeBlockIndex(portal: Portal) {
        if (portal.sourceShape is PortalShape.Block) {
            byBlockPosition.remove(BlockKey(portal.sourceWorld, portal.sourceShape.position))
        }
    }

    override fun findByPosition(world: WorldId, position: Vec3): Portal? {
        byBlockPosition[BlockKey(world, position)]?.let { return it }
        // Linear walk over region-shape portals in the same world.
        for (portal in byId.values) {
            if (portal.sourceWorld != world) continue
            val shape = portal.sourceShape
            if (shape is PortalShape.Region && shape.region.contains(position)) {
                return portal
            }
        }
        return null
    }

    override fun all(): Collection<Portal> = byId.values.toList()
}
