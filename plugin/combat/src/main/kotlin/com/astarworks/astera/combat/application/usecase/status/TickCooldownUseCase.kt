package com.astarworks.astera.combat.application.usecase.status

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Periodic tick use case invoked by the platform scheduler (Stage 7-改).
 *
 * For Stage 3-改 this use case is intentionally minimal: it holds a reference
 * to [IStatusEffectContainer] so the platform scheduler can call [execute]
 * on each game tick. Full status-expiry logic (decrementing [remainingTicks],
 * publishing StatusExpired events) is deferred to Stage 7-改 when the Paper
 * adapter and tick-accurate clock are available.
 *
 * TODO (Stage 7-改): add `fun tick(deltaMs: Long): Set<StatusEffectInstance>` to
 * IStatusEffectContainer, decrement remaining ticks here, and publish
 * `StatusExpired` for each expired instance.
 */
public class TickCooldownUseCase(
    private val statusContainer: IStatusEffectContainer,
    private val trackedPlayers: () -> Set<PlayerId> = { emptySet() },
) {
    /**
     * Called every game tick from the platform scheduler.
     * Currently a no-op placeholder; Stage 7-改 will add expiry logic.
     */
    public fun execute() {
        // Stage 7-改: iterate trackedPlayers(), decrement remainingTicks,
        // call statusContainer.remove() for expired effects, publish StatusExpired.
    }
}
