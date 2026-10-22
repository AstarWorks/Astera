package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.hud.status.CooldownHudLayout
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port (ADR-0195): what the status line shows next to the HP bar for
 * the per-binding cooldown icons. Core owns the status line channel
 * (`HpStatusHudService`); the combat plugin owns cooldowns and registers the
 * implementation in the `ServiceRegistry`.
 */
public fun interface ICooldownIconSource {

    /**
     * One entry per bound input of the item [playerId] holds, ready or not;
     * empty when the held item binds no skill.
     */
    public fun entriesFor(playerId: PlayerId): List<CooldownHudLayout.Entry>
}
