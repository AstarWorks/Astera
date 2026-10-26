package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Domain-level "player left the server" signal (Wave 5h).
 *
 * Counterpart to [PlayerJoinedServer]. Distinct from `IMcEvent.PlayerQuit` —
 * that one is an adapter-layer Bukkit-bridge event and intentionally lives
 * outside the domain bus. `PlayerQuitServer` is the **domain** counterpart,
 * publishable by any adapter without coupling subscribers to Bukkit. Wave 6b's
 * `BukkitEventAdapter.onPlayerQuit` will translate the Paper event into this
 * domain variant.
 *
 * Initial subscriber: `DisconnectDuringMatchListener` in the Match plugin,
 * which freezes ACTIVE-phase players into [com.astarworks.astera.core.domain.player.SessionState.Disconnected]
 * for a 3-min reconnect window or kicks during COUNTDOWN / forfeits during
 * SUDDEN_DEATH.
 *
 * The payload stays intentionally lean (id + timestamp) so subscribers cannot
 * drift toward Paper-tied payloads — anything richer (display name, channel,
 * etc.) must be derived from the bridge adapter, not from this domain event.
 */
public data class PlayerQuitServer(
    val playerId: PlayerId,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
