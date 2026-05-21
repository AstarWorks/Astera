package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.permission.Permission

/**
 * Point-in-time predicate evaluated against a player's current state to
 * choose a [DialogNode.Condition] branch (Wave 5p, ADR-0093).
 *
 * # Distinct from [com.astarworks.astera.core.domain.advancement.AdvancementCriteria]
 *
 * Advancement criteria are **counter-based** — they accumulate "have you
 * killed 10 enemies cumulatively" across sessions. Dialog predicates are
 * **point-in-time snapshots** — "do you currently have this item in your
 * inventory" / "do you currently have this much currency". Unifying the two
 * would force one mode on both surfaces; keeping them parallel is cheaper
 * than the abstraction.
 *
 * # Predicate vocabulary shared with [com.astarworks.astera.core.domain.tutorial.TutorialPredicate]
 *
 * The tutorial sequencer (Worker γ) re-uses exactly this vocabulary for its
 * own condition steps. The two are kept as separate sealed hierarchies (not
 * a typealias) so each domain stays self-contained — if tutorials add a
 * predicate that's not meaningful for dialogs, the dialog side's exhaustive
 * `when` doesn't get a no-op branch.
 *
 * # Evaluation
 *
 * [com.astarworks.astera.core.application.usecase.dialog.AdvanceNpcDialogUseCase]
 * resolves a [DialogPredicate] by looking up the relevant outbound services
 * (`IItemRegistry` + inventory query, `ICurrencyLedger.balance`,
 * `IPlayerAdvancementProgressRepository.completed`, `IPermissionService.has`).
 * The evaluation result is opaque to the domain — `true` chooses `ifTrue`,
 * `false` chooses `ifFalse`.
 */
public sealed interface DialogPredicate {

    /** Player has at least [count] of [itemId] in their inventory right now. */
    public data class HasItem(public val itemId: ItemId, public val count: Int = 1) : DialogPredicate {
        init {
            require(count > 0) { "DialogPredicate.HasItem.count must be > 0, got $count" }
        }
    }

    /** Player has at least [amount] of [currency] in their wallet right now. */
    public data class HasCurrency(public val currency: Currency, public val amount: Long) : DialogPredicate {
        init {
            require(amount > 0) { "DialogPredicate.HasCurrency.amount must be > 0, got $amount" }
        }
    }

    /** Player has earned [advancementId] (latched, not in-progress). */
    public data class HasAdvancement(public val advancementId: AdvancementId) : DialogPredicate

    /** Player satisfies the [permission] check via the permission service. */
    public data class PermissionCheck(public val permission: Permission) : DialogPredicate
}
