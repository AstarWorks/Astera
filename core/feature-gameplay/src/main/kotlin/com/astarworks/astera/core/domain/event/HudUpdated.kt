package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A live HUD handle's layout was replaced (Wave 5j, ADR-0097).
 *
 * Published by `IHudGateway.updateFor` implementations on dispatch
 * success — does NOT imply the player's client has re-rendered yet.
 *
 * Subscribers wanting "the layout currently shown to player X" should
 * combine this with [HudShown] and [HudCleared] to reconstruct the per
 * handle layout. The event is emitted **per update call** so progress
 * bar tick streams (mana / cooldown) will publish at the same cadence
 * the caller updates the handle — subscribers that don't want the firehose
 * should filter by handle or throttle.
 *
 * @property playerId owning player, denormalised so subscribers don't
 *   need to look up the gateway's internal active-map.
 */
public data class HudUpdated(
    val playerId: PlayerId,
    val handle: HudHandle,
    val layout: HudLayout,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
