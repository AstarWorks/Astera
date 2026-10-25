package com.astarworks.astera.core.application.port.outbound.item

import com.astarworks.astera.core.domain.item.WeaponLimits
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: the weapon cap that applies to a player right now. Match registers it (it knows
 * the player's match and the mode's `weapon_limits`); outside a match there is no cap.
 */
public fun interface IWeaponLimitProvider {
    /** The cap for a player currently in a match, or `null` when no cap applies (lobby, no match). */
    public fun limitsOf(playerId: PlayerId): WeaponLimits?
}
