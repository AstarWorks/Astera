package com.astarworks.astera.core.application.service.dialog

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDialogPredicateEvaluator
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerAdvancementProgressRepository
import com.astarworks.astera.core.domain.dialog.DialogPredicate
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Default [IDialogPredicateEvaluator] composing core-side outbound services
 * (Wave 5p, ADR-0088).
 *
 *  - [DialogPredicate.HasCurrency] → [ICurrencyLedger.balance] ≥ amount.
 *  - [DialogPredicate.HasAdvancement] →
 *    [IPlayerAdvancementProgressRepository.find] non-null + `completedAt`
 *    non-null.
 *  - [DialogPredicate.PermissionCheck] → [IPermissionService.check].
 *  - [DialogPredicate.HasItem] → handled by an optional [inventoryProbe]
 *    closure; Phase 1 PoC ships with a `null` probe that returns false
 *    (no inventory port in core), and Wave 6b provides the Paper-side
 *    closure that queries the player's inventory contents via the items
 *    plugin's `IItemDelivery`-adjacent read port.
 *
 * # Test seam
 *
 * The constructor takes a `HasItem` probe rather than an `IItemRegistry` +
 * inventory port so tests can compose evaluators without hauling in adapter
 * plumbing. Default closure: returns `false` for [DialogPredicate.HasItem]
 * (no inventory access in core).
 */
public class DefaultDialogPredicateEvaluator(
    private val ledgerProvider: () -> ICurrencyLedger?,
    private val advancementRepo: IPlayerAdvancementProgressRepository,
    private val permissions: IPermissionService,
    private val inventoryProbe: (PlayerId, DialogPredicate.HasItem) -> Boolean = { _, _ -> false },
) : IDialogPredicateEvaluator {

    override fun evaluate(playerId: PlayerId, predicate: DialogPredicate): Boolean = when (predicate) {
        is DialogPredicate.HasItem -> inventoryProbe(playerId, predicate)
        is DialogPredicate.HasCurrency -> {
            // Lazy ledger lookup: economy plugin loads after core, so the
            // ledger may be unavailable at evaluator construction time.
            // Treat "ledger missing" as predicate=false (graceful degrade).
            val ledger = ledgerProvider()
            if (ledger == null) false
            else ledger.balance(playerId, predicate.currency) >= predicate.amount
        }
        is DialogPredicate.HasAdvancement -> {
            val row = advancementRepo.find(playerId, predicate.advancementId)
            row?.completedAt != null
        }
        is DialogPredicate.PermissionCheck -> permissions.check(playerId, predicate.permission)
    }
}
