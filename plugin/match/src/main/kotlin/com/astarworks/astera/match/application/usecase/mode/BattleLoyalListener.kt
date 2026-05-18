package com.astarworks.astera.match.application.usecase.mode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.match.domain.mode.BattleLoyal

/**
 * Lifecycle hook for [BattleLoyal] mode (Wave 5c).
 *
 * BattleLoyal's victory condition (score threshold) is already evaluated by
 * [com.astarworks.astera.match.application.usecase.match.VictoryConditionEvaluator]
 * (ADR-0027), so this listener does **not** publish [MatchEnded] itself.
 * It exists for symmetry with the other mode listeners — every game mode
 * gets a `<Mode>Listener` Phase-C-wired into [AsteraMatchPlugin], so adding
 * a new mode is a uniform 3-file edit and per-mode behaviour has an obvious
 * home when it lands later (HUD scoreboard hooks, taunt sounds, etc.).
 *
 * Phase 1 PoC: subscribes to [PhaseChanged] purely so the subscription has
 * a real subject (verifies wiring at runtime); the handler is a no-op.
 * Remove the subscription once a real behaviour hook arrives.
 */
public class BattleLoyalListener(
    private val eventBus: IDomainEventBus,
    private val mode: BattleLoyal = BattleLoyal(),
) {

    /**
     * Subscribe this listener to the event bus. Returns the subscription so
     * the caller (Phase C `AsteraMatchPlugin.onDisable`) can cancel it.
     */
    public fun subscribe(): IDomainEventBus.Subscription =
        eventBus.subscribe(PhaseChanged::class) { _ ->
            // No-op: BattleLoyal's victory eval lives in VictoryConditionEvaluator.
            // The subscription exists so wiring failures surface in tests.
        }

    /** The mode value this listener is bound to (Phase C wiring uses this). */
    public fun mode(): BattleLoyal = mode
}
