package com.astarworks.astera.combat.adapter.paper

import java.util.UUID

/**
 * Rig displays whose owner-side copy was scaled to zero to hand the held item back (PaperEntityEffectsGateway.restoreHeldItem).
 * PaperDisplayEntityGateway stops updating them: a later transform update would be sent to the owner and bring the rig back
 * for the tick before it is removed. Main thread only.
 */
internal object ConcealedDisplays {
    private val ids: MutableSet<UUID> = HashSet()

    fun add(id: UUID) { ids += id }

    fun contains(id: UUID): Boolean = id in ids

    fun remove(id: UUID) { ids -= id }
}
