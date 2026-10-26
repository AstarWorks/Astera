package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/** Permanent entitlement IDs, namespaced by kind (title:, cosmetic:, item:). */
public interface IPlayerUnlockView {
    public fun unlocks(playerId: PlayerId): Set<String>
}

/** The write side of the same entitlements: shop purchases and achievement rewards grant through it. */
public interface IPlayerUnlockStore : IPlayerUnlockView {
    /** Idempotent. Returns true when the entitlement was newly added. */
    public fun grant(playerId: PlayerId, unlockId: String): Boolean
}
