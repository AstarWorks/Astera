package com.astarworks.astera.core.application.port.outbound.portal

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.portal.Portal
import com.astarworks.astera.core.domain.portal.PortalId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: registry of [Portal]s indexed for spatial lookup
 * (Wave 5block-embed, ADR-0120).
 *
 * Walk-on / right-click activation listeners ask "is there a portal at
 * this `(world, position)`?" 1–2 times per `PlayerMoved` event, so the
 * lookup path must avoid linear scans across all portals. The Phase 1
 * in-memory impl uses a position map for block-shape portals + a
 * per-world list for region-shape portals (typically ≤ 10 region
 * portals per world, so the linear walk inside a world is fine).
 *
 * # Threading
 *
 * Main-thread only. Phase 1 has no concurrent registry mutation.
 *
 * # Lifecycle
 *
 * Use cases publish [com.astarworks.astera.core.domain.event.PortalRegistered]
 * / [com.astarworks.astera.core.domain.event.PortalUnregistered] when they
 * call [register] / [unregister]; the gateway itself stays I/O-free so
 * subscribers control the bus dispatch ordering.
 */
public interface IPortalGateway {

    /**
     * Register [portal]. If a portal with the same id already exists, it
     * is replaced (idempotent semantics for hot-reload). Returns the
     * portal that was replaced, or `null` if this is a fresh
     * registration.
     */
    public fun register(portal: Portal): Portal?

    /**
     * Remove the portal with [id]. Returns `true` if anything was
     * removed.
     */
    public fun unregister(id: PortalId): Boolean

    /**
     * Find the portal at ([world], [position]). Resolution rule:
     *
     *  - For block-shape portals, exact `Vec3` match against
     *    [com.astarworks.astera.core.domain.portal.PortalShape.Block.position].
     *  - For region-shape portals, AABB-contains test against
     *    [com.astarworks.astera.core.domain.portal.PortalShape.Region.region].
     *
     * Returns the **first** matching portal (the in-memory impl iterates
     * in registration order). In Phase 1 we expect ≤ 1 portal per
     * coordinate; future waves may add a deterministic priority rule if
     * stage authors start overlapping portals on purpose.
     */
    public fun findByPosition(world: WorldId, position: Vec3): Portal?

    /** Returns every currently-registered portal in undefined order. */
    public fun all(): Collection<Portal>
}
