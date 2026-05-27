package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.portal.IPortalGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.portal.Portal
import com.astarworks.astera.core.domain.portal.PortalId
import com.astarworks.astera.core.domain.portal.PortalShape
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Test fake for [IPortalGateway] (Wave 5block-embed, ADR-0120).
 *
 * Same external contract as the production in-memory impl; records
 * register / unregister call counts so listener tests can assert on
 * lifecycle without depending on lookup-state.
 */
public class FakePortalGateway : IPortalGateway {

    public val registered: MutableList<Portal> = mutableListOf()
    public val unregistered: MutableList<PortalId> = mutableListOf()

    private val byId: MutableMap<PortalId, Portal> = mutableMapOf()

    override fun register(portal: Portal): Portal? {
        registered += portal
        return byId.put(portal.id, portal)
    }

    override fun unregister(id: PortalId): Boolean {
        unregistered += id
        return byId.remove(id) != null
    }

    override fun findByPosition(world: WorldId, position: Vec3): Portal? {
        for (portal in byId.values) {
            if (portal.sourceWorld != world) continue
            when (val shape = portal.sourceShape) {
                is PortalShape.Block -> if (shape.position == position) return portal
                is PortalShape.Region -> if (shape.region.contains(position)) return portal
            }
        }
        return null
    }

    override fun all(): Collection<Portal> = byId.values.toList()
}
