package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IItemCooldownGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.ResourceReloadStarted
import org.slf4j.LoggerFactory

/**
 * Reload progress without a boss bar (F2-2, ADR-0194): when a reload starts, the
 * held weapon gets the native item cooldown sweep for the reload time. The
 * remaining ammo is read from the status line (`HpStatusHudService`).
 */
public class ReloadCooldownOverlayListener(
    private val cooldowns: IItemCooldownGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun onReloadStarted(event: ResourceReloadStarted) {
        val ticks = event.durationTicks.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val outcome = cooldowns.showHeldItemCooldown(event.playerId, ticks)
        if (outcome is Result.Failure) log.debug("Reload cooldown overlay failed for {}: {}", event.playerId, outcome.error)
    }
}
