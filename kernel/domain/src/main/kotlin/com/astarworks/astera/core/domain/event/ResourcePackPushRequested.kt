package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A resource pack push was requested for a player (Wave 5j, ADR-0097).
 *
 * Published by `IHudGateway.pushResourcePack` implementations the moment
 * the call is dispatched — does NOT indicate the player's client has
 * downloaded, validated, or applied the pack. Subscribers waiting on the
 * end-to-end outcome should observe the player-status events that Wave 6b
 * `PaperHudGateway` will publish from `PlayerResourcePackStatusEvent`
 * (accepted / declined / failed).
 *
 * The Wave 5j default impl ([com.astarworks.astera.core.application.service.InMemoryHudGateway])
 * publishes this event but performs no real push — it is a pure no-op
 * placeholder so subscribers can be wired ahead of the Paper adapter.
 *
 * @property pack full spec including url, sha1Hash, required flag, and
 *   prompt key — captured here so audit subscribers can correlate the
 *   request with later status events.
 */
public data class ResourcePackPushRequested(
    val playerId: PlayerId,
    val pack: ResourcePackSpec,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
