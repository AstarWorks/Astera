package com.astarworks.astera.core.adapter.paper.presentation

import java.util.UUID

/**
 * Rig displays whose owner-side copy was scaled to zero to hand the held item back (PaperPlayerPresentation.release).
 * PaperDisplayEntityGateway stops updating them: a later transform update would be sent to the owner and bring the rig back
 * for the tick before it is removed. Main thread only.
 */
public object ConcealedDisplays {
    private val ids: MutableSet<UUID> = HashSet()

    public fun add(id: UUID) { ids += id }

    public fun contains(id: UUID): Boolean = id in ids

    public fun remove(id: UUID) { ids -= id }
}
