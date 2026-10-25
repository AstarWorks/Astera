package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile

/**
 * Durable store behind [IPlayerProfileService] (ADR-0195). The service keeps the
 * starter-grant logic; this port keeps the "has this player ever been onboarded"
 * fact, so a restart does not re-run the first-time grant (starter kit, 100 GOLD).
 * Persistence overlays the in-memory default at priority 100.
 */
public interface IPlayerProfileRepository {
    public fun find(playerId: PlayerId): PlayerProfile?

    /** Stores [profile] unless one exists. `true` = this call created it (first writer wins). */
    public fun insertIfAbsent(profile: PlayerProfile): Boolean
}
