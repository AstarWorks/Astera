package com.astarworks.astera.core.application.service.dialog

import com.astarworks.astera.core.application.port.outbound.INpcDialogBinding
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.entity.EntityHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [INpcDialogBinding] (Wave 5p, ADR-0088).
 *
 * Thread-safe `ConcurrentHashMap` keyed by [EntityHandle]. No persistence —
 * bindings are typically established at NPC-spawn time (the entity spawner
 * registers the dialog as part of the spawn pipeline) and unregistered on
 * despawn, so reboot semantics fall out for free.
 *
 * Phase 4 may revisit if persistent NPC bindings (creator-mode UGC) need to
 * survive restarts; that's a Postgres-backed overlay at priority 100, no
 * change to this port.
 */
public class InMemoryNpcDialogBinding : INpcDialogBinding {

    private val bindings = ConcurrentHashMap<EntityHandle, DialogTreeId>()

    override fun bind(handle: EntityHandle, treeId: DialogTreeId) {
        bindings[handle] = treeId
    }

    override fun resolve(handle: EntityHandle): DialogTreeId? = bindings[handle]

    override fun unbind(handle: EntityHandle) {
        bindings.remove(handle)
    }

    override fun all(): Map<EntityHandle, DialogTreeId> = bindings.toMap()
}
