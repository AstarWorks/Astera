package com.astarworks.astera.economy.application.service.betting

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingOptInProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1 in-memory [IBettingOptInProvider] (Wave 5bet / ADR-0103).
 *
 * Default behaviour: every player starts **disabled**. The Phase 2
 * `/astera-bet opt-in` command (or Wave 5t PlayerSettings UI) flips the
 * flag. The Postgres overlay in `:plugin:persistence` (added Wave 5bet)
 * mirrors `PlayerSettings.bettingOptedIn` columnar field, but the registry
 * remains the only application-layer surface.
 */
public class InMemoryBettingOptInProvider : IBettingOptInProvider {

    private val optedIn: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    override fun isOptedIn(playerId: PlayerId): Boolean = playerId in optedIn

    override fun setOptedIn(playerId: PlayerId, optedIn: Boolean) {
        if (optedIn) this.optedIn.add(playerId) else this.optedIn.remove(playerId)
    }

    /** Phase 1 helper for tests: drop everything. */
    public fun clear() {
        optedIn.clear()
    }
}
