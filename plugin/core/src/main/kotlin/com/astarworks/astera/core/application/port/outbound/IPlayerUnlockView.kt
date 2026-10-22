package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/** Permanent entitlement IDs, namespaced by kind (title:, cosmetic:, item:). */
public interface IPlayerUnlockView {
    public fun unlocks(playerId: PlayerId): Set<String>
}
