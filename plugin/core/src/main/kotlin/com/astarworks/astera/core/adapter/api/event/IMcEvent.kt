package com.astarworks.astera.core.adapter.api.event

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer

/**
 * Vendor-neutral player / world interaction events.
 *
 * Concrete server cores (Paper, Folia, ...) translate their native events
 * into [IMcEvent] subclasses. Application-layer use cases listen via simple
 * callbacks; no Bukkit Listener interface leaks past this boundary.
 *
 * Phase 1 covers just enough for weapon firing. Phase 2+ adds block place /
 * break, entity damage, inventory click, etc.
 */
sealed interface IMcEvent {
    val player: IMcPlayer

    data class PlayerLeftClickAir(override val player: IMcPlayer) : IMcEvent
    data class PlayerLeftClickBlock(override val player: IMcPlayer) : IMcEvent
}
