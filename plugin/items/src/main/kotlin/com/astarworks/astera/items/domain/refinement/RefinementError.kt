package com.astarworks.astera.items.domain.refinement

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Sealed failure mode for refinement use cases (Wave 5refine, ADR-0100).
 *
 * Honors the "no raw JVM throwables in application API" rule — every
 * `ApplyRefinementUseCase` / `RerollSubStatsUseCase` returns
 * `Result<T, RefinementError>` so callers must handle each failure
 * explicitly. The Konsist test
 * `application layers must not import raw JVM throwable types` enforces
 * this on the application package.
 */
public sealed class RefinementError {
    public abstract val messageKey: MessageKey

    /** Item template not found in the registry; caller passed an unknown id. */
    public data class UnknownItem(val itemId: ItemId) : RefinementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.items.refinement.unknown_item")
    }

    /** Item template loaded but its [RefinementSpec] is disabled / empty. */
    public data class NotRefinable(val itemId: ItemId) : RefinementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.items.refinement.not_refinable")
    }

    /** Requested layer kind is not allowed on this item (e.g. gemstone on a no-slot item). */
    public data class LayerNotAllowed(val itemId: ItemId, val reason: String) : RefinementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.items.refinement.layer_not_allowed")
    }

    /** Sub-stat axis is not in the item's `candidate_sub_stats` whitelist. */
    public data class SubStatNotCandidate(
        val itemId: ItemId,
        val key: SubStatKey,
    ) : RefinementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.items.refinement.sub_stat_not_candidate")
    }

    /**
     * Wallet lacks the escalating reroll cost (see
     * [RefinementMath.rerollCost]). Carries the currency + shortfall so the
     * UI can render the exact gap.
     */
    public data class InsufficientFunds(
        val currency: Currency,
        val have: Long,
        val tried: Long,
    ) : RefinementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.items.refinement.insufficient_funds")
    }

    /** Player tried to apply an instance that does not exist on the repository. */
    public data class UnknownInstance(val id: ItemInstanceId) : RefinementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.items.refinement.unknown_instance")
    }
}
