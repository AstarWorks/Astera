package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IGodModeService
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/** Default [IGodModeService]: a concurrent set of god players. */
public class InMemoryGodModeService : IGodModeService {

    private val gods: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    override fun isGod(playerId: PlayerId): Boolean = playerId in gods

    override fun setGod(playerId: PlayerId, enabled: Boolean) {
        if (enabled) gods += playerId else gods -= playerId
    }
}
