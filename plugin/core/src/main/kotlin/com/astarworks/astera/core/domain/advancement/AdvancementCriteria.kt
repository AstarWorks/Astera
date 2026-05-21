package com.astarworks.astera.core.domain.advancement

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Predicate evaluated against per-player progress counters to determine
 * whether an advancement has been earned (Wave 5p, ADR-0088).
 *
 * # Composable shape
 *
 * Leaf variants ([KillCount], [CurrencyEarned], [MatchesWon], [ItemAcquired])
 * each have a single concrete progress signal — the
 * [com.astarworks.astera.core.application.usecase.advancement.ProgressAdvancementUseCase]
 * increments a counter when the matching domain event fires
 * (see [com.astarworks.astera.core.domain.event.PlayerKilled],
 * [com.astarworks.astera.core.domain.event.MatchEnded],
 * [com.astarworks.astera.core.domain.event.CurrencyChangeReason],
 * [com.astarworks.astera.core.domain.event.ItemAcquired]).
 *
 * [AllOf] / [AnyOf] are composite: an advancement with `AllOf([A, B])` needs
 * **both** child criteria's `targetReached()` to be true; `AnyOf([A, B])` needs
 * **either**. Composites recurse so trees of arbitrary depth are legal — the
 * YAML schema controls how authors express them.
 *
 * # Shared with [com.astarworks.astera.core.domain.dialog.DialogPredicate]
 *
 * Wave 5p deliberately keeps the two surfaces separate even though several
 * leaf variants overlap (`HasItem` / `ItemAcquired`, `HasCurrency` /
 * `CurrencyEarned`). The semantics differ: advancement criteria are
 * **counter-based** (cumulative progress), dialog predicates are **point-in-time
 * snapshots** ("do you have X right now?"). Unifying them would force one
 * shape onto both use cases and produce the wrong defaults for both. The
 * **predicate vocabulary** (HasItem / HasCurrency / HasAdvancement /
 * PermissionCheck) is shared via tutorial sequencer reuse (see
 * [com.astarworks.astera.core.domain.tutorial.TutorialPredicate]).
 */
public sealed interface AdvancementCriteria {

    /** Cumulative player kills required. Listens for [com.astarworks.astera.core.domain.event.PlayerKilled]. */
    public data class KillCount(public val target: Int) : AdvancementCriteria {
        init {
            require(target > 0) { "AdvancementCriteria.KillCount.target must be > 0, got $target" }
        }
    }

    /** Cumulative positive [Currency] delta required (sums positive deltas only). */
    public data class CurrencyEarned(
        public val currency: Currency,
        public val amount: Long,
    ) : AdvancementCriteria {
        init {
            require(amount > 0) { "AdvancementCriteria.CurrencyEarned.amount must be > 0, got $amount" }
        }
    }

    /** Cumulative matches whose end-event the player was on the winning team. */
    public data class MatchesWon(public val count: Int) : AdvancementCriteria {
        init {
            require(count > 0) { "AdvancementCriteria.MatchesWon.count must be > 0, got $count" }
        }
    }

    /** A specific [ItemId] was acquired at least once (latched). */
    public data class ItemAcquired(public val itemId: ItemId) : AdvancementCriteria

    /** Composite: every child criterion must be satisfied. */
    public data class AllOf(public val children: List<AdvancementCriteria>) : AdvancementCriteria {
        init {
            require(children.isNotEmpty()) { "AdvancementCriteria.AllOf must have at least one child" }
        }
    }

    /** Composite: any one child criterion satisfies the parent. */
    public data class AnyOf(public val children: List<AdvancementCriteria>) : AdvancementCriteria {
        init {
            require(children.isNotEmpty()) { "AdvancementCriteria.AnyOf must have at least one child" }
        }
    }
}
