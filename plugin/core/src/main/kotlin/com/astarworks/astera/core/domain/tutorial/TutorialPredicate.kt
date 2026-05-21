package com.astarworks.astera.core.domain.tutorial

import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.permission.Permission

/**
 * Point-in-time predicate for [TutorialStep.AwaitPredicate] (Wave 5p, ADR-0088).
 *
 * # Parallel definition with [com.astarworks.astera.core.domain.dialog.DialogPredicate]
 *
 * The brief identified the same predicate vocabulary
 * (HasItem / HasCurrency / HasAdvancement / PermissionCheck) for both NPC
 * dialogs and tutorial sequencer steps. Wave 5p keeps them as **two parallel
 * sealed hierarchies** rather than collapsing into one because:
 *
 *   - Future variants may diverge: a tutorial may want
 *     `OnLadderFor(durationTicks)` (movement-based) that has no dialog
 *     equivalent; a dialog may want `BetweenServerHours(start, end)` that
 *     has no tutorial equivalent.
 *   - Keeping the two `when` exhaustivity surfaces independent lets each
 *     surface evolve without bleed.
 *
 * The actual evaluator is shared at the listener level — Wave 5p's
 * `AdvanceTutorialSequencerUseCase` (Worker γ Phase C wiring) takes the
 * same [com.astarworks.astera.core.application.port.outbound.IDialogPredicateEvaluator]
 * via a small adapter that lifts [TutorialPredicate] onto
 * [com.astarworks.astera.core.domain.dialog.DialogPredicate] (one-to-one for
 * Wave 5p; mapping degrades gracefully if either side adds a unique variant).
 */
public sealed interface TutorialPredicate {

    public data class HasItem(public val itemId: ItemId, public val count: Int = 1) : TutorialPredicate {
        init {
            require(count > 0) { "TutorialPredicate.HasItem.count must be > 0, got $count" }
        }
    }

    public data class HasCurrency(public val currency: Currency, public val amount: Long) : TutorialPredicate {
        init {
            require(amount > 0) { "TutorialPredicate.HasCurrency.amount must be > 0, got $amount" }
        }
    }

    public data class HasAdvancement(public val advancementId: AdvancementId) : TutorialPredicate

    public data class PermissionCheck(public val permission: Permission) : TutorialPredicate
}
