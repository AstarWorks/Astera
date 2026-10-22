package com.astarworks.astera.combat.application.eventbus.listeners.status

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.PlayerKilled

/**
 * Death wipes every active status on the victim. Without this, burn (and
 * other DoTs) survive the respawn teleport and can kill the respawned player
 * again, and buffs/debuffs carry across lives.
 */
public class ClearStatusesOnPlayerKilledListener(
    private val statusContainer: IStatusEffectContainer,
) {
    public fun handle(event: PlayerKilled) {
        statusContainer.clear(event.victim)
    }
}
