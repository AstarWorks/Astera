package com.astarworks.astera.core.adapter.api.server

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Vendor-neutral server abstraction. Sufficient for Phase 1 operations
 * (player lookup); Phase 2+ adds world / scheduler / pluginManager facets.
 */
interface IMcServer {
    fun findPlayer(id: PlayerId): IMcPlayer?
    fun findPlayerByName(name: String): IMcPlayer?
}
