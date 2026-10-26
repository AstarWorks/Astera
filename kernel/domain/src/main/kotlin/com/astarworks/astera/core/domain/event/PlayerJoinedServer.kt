package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Domain-level "player joined the server" signal (Wave 5e / RTM issue #5).
 *
 * Distinct from `IMcEvent.PlayerJoined` — that one is an adapter-layer
 * Bukkit-bridge event and intentionally lives outside the domain bus.
 * `PlayerJoinedServer` is the **domain** counterpart, publishable by any
 * adapter without coupling subscribers to Bukkit. Wave 6b's
 * `BukkitEventAdapter.onPlayerJoin` will translate the Paper event into this
 * domain variant.
 *
 * Initial subscriber: `PlayerHandoffService`, which fires
 * `RestoreInventorySnapshotUseCase`. Future Wave-6b subscribers may include
 * the tutorial-step resume listener and the level-up greeting listener.
 *
 * Note: `IMcEvent.PlayerJoined` is the ONLY place the player's display name
 * is available cheaply on Paper. Callers that need it should derive it from
 * the bridge adapter, not from this domain event — the domain event stays
 * intentionally lean (id + timestamp) so subscribers cannot drift toward
 * Paper-tied payloads.
 */
public data class PlayerJoinedServer(
    val playerId: PlayerId,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
