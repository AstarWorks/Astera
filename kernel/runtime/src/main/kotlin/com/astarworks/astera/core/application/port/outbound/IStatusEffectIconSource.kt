package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.hud.status.StatusEffectHudLayout
import com.astarworks.astera.core.domain.player.PlayerId

/** Combat supplies effect snapshots; Core remains the status line's only writer. */
public fun interface IStatusEffectIconSource {
    public fun entriesFor(playerId: PlayerId): List<StatusEffectHudLayout.Entry>
}
