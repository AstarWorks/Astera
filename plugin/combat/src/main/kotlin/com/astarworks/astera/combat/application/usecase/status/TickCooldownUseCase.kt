package com.astarworks.astera.combat.application.usecase.status

import com.astarworks.astera.combat.domain.event.CooldownExpired
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Periodic tick use case invoked by the platform scheduler every game tick.
 *
 * Delegates to [ICooldownTracker.tick] to scan for expired cooldown entries
 * and publishes a [CooldownExpired] event on [IDomainEventBus] for each
 * resulting `(playerId, key)` pair. The set is also returned so the platform
 * wiring (AsteraCombatPlugin) may run additional ad-hoc follow-up logic
 * without re-subscribing.
 *
 * Wired by `scheduler.runRepeatingTicks(1L, 1L) { execute() }` in
 * `AsteraCombatPlugin.onEnable`. ADR-0155 (Wave 6a-N2) records the wiring +
 * event publish; ADR-0134 (publisher-must-exist) is satisfied because the
 * publisher and the event ship together in this wave.
 */
public class TickCooldownUseCase(
    private val cooldownTracker: ICooldownTracker,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) {
    /**
     * Called every game tick from the platform scheduler.
     *
     * Each expired pair triggers a [CooldownExpired] publish on [eventBus]
     * before this method returns. Publish failures are not swallowed —
     * [IDomainEventBus.publish] is synchronous by contract (silly-knuth §5),
     * so a subscriber exception will surface to the scheduler's tick wrapper.
     *
     * @return the set of (playerId, cooldownKey) pairs whose cooldowns
     *         expired at the current tick. Empty when nothing expired.
     */
    public fun execute(): Set<Pair<PlayerId, CooldownKey>> {
        val currentTick = scheduler.currentTick()
        val expired = cooldownTracker.tick(currentTick)
        for ((playerId, key) in expired) {
            eventBus.publish(
                CooldownExpired(
                    playerId = playerId,
                    cooldownKey = key,
                    expiredAtTick = currentTick,
                ),
            )
        }
        return expired
    }
}
