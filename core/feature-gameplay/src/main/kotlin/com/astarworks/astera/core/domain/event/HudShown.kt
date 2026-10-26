package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A [HudLayout] was successfully shown for a player (Wave 5j, ADR-0097).
 *
 * Published by `IHudGateway.showFor` implementations
 * ([com.astarworks.astera.core.application.service.InMemoryHudGateway],
 * Wave 6b's `PaperHudGateway`, and test-fixtures' `FakeHudGateway`) on
 * dispatch success — does NOT imply the player's client has actually
 * rendered yet (resource pack may still be downloading).
 *
 * Initial subscribers: audit logger (Wave 5j+ HUD event trail) and
 * analytics. The companion lifecycle events are [HudUpdated] (layout
 * replaced on a live handle) and [HudCleared] (player's region wiped).
 *
 * @property handle opaque id returned by `showFor`. Persistent until
 *   `clearFor` or plugin restart.
 * @property layout the layout pushed to the client. Captured so audit /
 *   analytics subscribers can record what was shown without keeping a
 *   reference to the gateway's internal map.
 */
public data class HudShown(
    val playerId: PlayerId,
    val handle: HudHandle,
    val layout: HudLayout,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
