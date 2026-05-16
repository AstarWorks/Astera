package com.astarworks.astera.adapter.minecraftapi.server

import com.astarworks.astera.adapter.minecraftapi.entity.IMcPlayer
import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Vendor-neutral server abstraction. Sufficient for Phase 1 operations
 * (player lookup); Phase 2+ adds world / scheduler / pluginManager facets.
 */
interface IMcServer {
    fun findPlayer(id: PlayerId): IMcPlayer?
    fun findPlayerByName(name: String): IMcPlayer?
}
