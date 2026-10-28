package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * The match navigation compass: a locked vanilla COMPASS in hotbar slot 9 (index 8, `HotbarLayout.MATCH_COMPASS_SLOT`).
 * It cannot be dropped, moved or lost on death. Modes show their navigation (action-bar arrow, hologram guide) only while
 * a player [isHeld] it. Hosts own the item (Paper: a tagged ItemStack and its lock listener).
 */
public interface IMatchCompass {
    /** Puts the compass into slot index 8 of [player] if it is not there already (idempotent; offline players are skipped). */
    public fun give(player: PlayerId)

    /** Takes the compass away (match over). */
    public fun remove(player: PlayerId)

    /** True while [player] has the compass selected in the hotbar. */
    public fun isHeld(player: PlayerId): Boolean
}
