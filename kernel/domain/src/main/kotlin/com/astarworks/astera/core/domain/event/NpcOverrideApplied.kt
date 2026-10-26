package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.perviewer.NpcViewerOverride
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a per-viewer NPC override is staged on
 * [com.astarworks.astera.core.application.port.outbound.perviewer.npc.IPerViewerNpcStateGateway]
 * (Wave 5y-ext, ADR-0112).
 *
 * Fires on **every** mutator call that actually changes state — the gateway
 * suppresses the event when the new payload equals the current one (no-op
 * re-apply). Subscribers include:
 *  - Audit log writer (`/astera audit per-viewer`)
 *  - Wave 5w match recording capture (records per-viewer divergence so
 *    replay can reconstruct what each spectator saw)
 *  - Wave 5y-ext follow-ups: HUD update on glow toggle, mini-map update on
 *    name-visible toggle, spectator notification on equipment swap.
 *
 * [snapshot] is the **post-mutation** view of every override applied to
 * (viewer, npc); subscribers that need before/after diffing must cache the
 * previous snapshot themselves.
 */
public data class NpcOverrideApplied(
    val viewer: PlayerId,
    val npc: EntityHandle,
    val snapshot: NpcViewerOverride,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
