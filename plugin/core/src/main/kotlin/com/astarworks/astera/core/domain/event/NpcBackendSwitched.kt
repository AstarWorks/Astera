package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcBackendId
import java.time.Instant

/**
 * Published by the spawner / router when a previously-existing NPC handle
 * transitions from one backend to another — typically at Citizens
 * install / uninstall time, where existing armor-stand NPCs may be
 * re-spawned as Citizens NPCs (or vice-versa) without operator action.
 *
 * Phase 1: emitted only by tests; Wave 6b Paper-side `CitizensNpcAdapter`
 * publishes it for real when Citizens loads or unloads. Subscribers
 * (Wave 5g `IShopVendor`) re-key their bindings off [oldHandle] →
 * [newHandle].
 */
public data class NpcBackendSwitched(
    val oldHandle: EntityHandle,
    val newHandle: EntityHandle,
    val oldBackend: NpcBackendId,
    val newBackend: NpcBackendId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(oldBackend != newBackend) {
            "NpcBackendSwitched: oldBackend and newBackend must differ; both were $oldBackend"
        }
    }
}
