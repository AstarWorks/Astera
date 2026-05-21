package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.dialog.DialogPredicate
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: resolve a [DialogPredicate] against the player's current state
 * (Wave 5p, ADR-0088).
 *
 * # Why a port (not direct calls inside the use case)
 *
 * The dialog runner cannot directly call `IItemRegistry` + inventory query,
 * `ICurrencyLedger.balance`, `IPlayerAdvancementProgressRepository.completed`,
 * and `IPermissionService.has` because:
 *
 *   - The inventory-side "do you have N of X" query is not a single core
 *     port (it depends on what counts as "have" — equipped? in main inventory?
 *     vault? Phase 4 may want different semantics).
 *   - Tests want a one-line stub instead of wiring four real services.
 *
 * So the use case takes one [IDialogPredicateEvaluator] and the production
 * default ([com.astarworks.astera.core.application.service.dialog.DefaultDialogPredicateEvaluator])
 * composes the underlying services. Wave 6b's Paper adapter can swap in a
 * richer evaluator without touching the use case.
 *
 * The reuse extends to tutorials: the tutorial sequencer (Worker γ) takes the
 * same evaluator for its `AwaitPredicate(...)` step.
 */
public interface IDialogPredicateEvaluator {
    public fun evaluate(playerId: PlayerId, predicate: DialogPredicate): Boolean
}
