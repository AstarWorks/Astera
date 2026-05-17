package com.astarworks.astera.combat.application.usecase.status

import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Periodic tick use case invoked by the platform scheduler every game tick.
 *
 * Delegates to [ICooldownTracker.tick] to scan for expired cooldown entries
 * and returns the set of expired (player, key) pairs. The caller (platform
 * scheduler wiring in AsteraCombatPlugin) may publish events or perform
 * follow-up logic for each expired pair.
 *
 * Event publishing (e.g. `CooldownExpired`) is intentionally deferred from
 * here to keep this use case pure and testable without an event bus. Add
 * a `CooldownExpired` domain event and inject [IDomainEventBus] when the
 * platform layer needs to react to individual expirations.
 */
public class TickCooldownUseCase(
    private val cooldownTracker: ICooldownTracker,
    private val scheduler: IScheduler,
) {
    /**
     * Called every game tick from the platform scheduler.
     *
     * @return the set of (playerId, cooldownKey) pairs whose cooldowns
     *         expired at the current tick. Empty when nothing expired.
     */
    public fun execute(): Set<Pair<PlayerId, CooldownKey>> =
        cooldownTracker.tick(scheduler.currentTick())
}
