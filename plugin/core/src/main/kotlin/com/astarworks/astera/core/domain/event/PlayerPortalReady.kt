package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-server portal signal (Wave 5e).
 *
 * Published by `MatchSpawnUseCase` on the game-server side ([EntryReady])
 * and by `PlayerHandoffService` on the lobby-server
 * restore side ([ExitReady]). Wave 6b's `BukkitEventAdapter` will publish
 * these in response to Bukkit `PlayerJoinEvent` / `PlayerQuitEvent`.
 *
 * Cross-server reward bridge + audit log + future federation subscribe.
 * RTM source: `event/data/PlayerPortalReadyEvent.kt`.
 */
public sealed interface PlayerPortalReady : DomainEvent {
    public val playerId: PlayerId

    public data class EntryReady(
        override val playerId: PlayerId,
        val matchId: MatchId,
        override val occurredAt: Instant = Instant.now(),
        override val version: Int = 1,
    ) : PlayerPortalReady

    public data class ExitReady(
        override val playerId: PlayerId,
        val returnToLobby: Boolean,
        override val occurredAt: Instant = Instant.now(),
        override val version: Int = 1,
    ) : PlayerPortalReady
}
