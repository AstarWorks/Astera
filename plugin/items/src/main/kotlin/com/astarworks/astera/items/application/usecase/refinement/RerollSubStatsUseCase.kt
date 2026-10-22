package com.astarworks.astera.items.application.usecase.refinement

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.event.ItemInstanceRerolled
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.port.outbound.IItemInstanceRepository
import com.astarworks.astera.items.application.port.outbound.IRefinementRandomizer
import com.astarworks.astera.items.domain.refinement.ItemInstance
import com.astarworks.astera.items.domain.refinement.ItemInstanceId
import com.astarworks.astera.items.domain.refinement.RefinementError
import com.astarworks.astera.items.domain.refinement.RefinementMath
import com.astarworks.astera.items.domain.refinement.RefinementSpec
import com.astarworks.astera.items.domain.refinement.SubStatRoll

/**
 * Reroll the sub-stat set of an [ItemInstance] (Wave 5refine, ADR-0100
 * §"RerollSubStatsUseCase").
 *
 * Implements the Warframe Riven 反例 教訓 from
 * `docs/competitive-analysis/05-warframe.md`:
 *
 *   - Roll the *candidate* set (does not yet replace existing rolls).
 *   - Return the candidate alongside the existing roll set so the *caller*
 *     decides keep vs discard.
 *   - The follow-up `commit(...)` step writes the chosen branch.
 *
 * # Two-step "preview + commit"
 *
 * Phase 1 PoC keeps the rollout simple: one method, [reroll], takes a
 * `keepIfBetter` predicate so headless callers (tests / API smoke) get a
 * single round-trip while the Wave 6 inspect-GUI still has access to the
 * raw candidate via the [Outcome.candidate] field. The predicate defaults
 * to *naive sum-of-magnitudes greater-than* — adequate Phase 1 placeholder.
 *
 * # Cost
 *
 * Reroll is the *only* path that charges currency at the domain layer
 * (per ADR-0100 §"Resource sink"). The escalating ladder lives in
 * [RefinementMath.rerollCost]. Withdraw is done up-front via the supplied
 * [Wallet]; failure returns [RefinementError.InsufficientFunds] and the
 * roll is NOT executed. Event publication happens only on the success path.
 */
public class RerollSubStatsUseCase(
    private val items: IItemRegistry,
    private val instances: IItemInstanceRepository,
    private val randomizer: IRefinementRandomizer,
    private val eventBus: IDomainEventBus,
    private val refinementSpecResolver: (ItemId) -> RefinementSpec,
    private val rerollCurrency: Currency = Currency.GOLD,
) {

    public data class Outcome(
        public val instance: ItemInstance,
        public val previous: List<SubStatRoll>,
        public val candidate: List<SubStatRoll>,
        public val kept: Boolean,
        public val costPaid: Long,
    )

    /** Default predicate: keep iff the new total magnitude is strictly greater than the old. */
    public companion object {
        public val KEEP_IF_HIGHER_SUM: (List<SubStatRoll>, List<SubStatRoll>) -> Boolean =
            { previous, candidate -> candidate.sumOf { it.magnitude } > previous.sumOf { it.magnitude } }
    }

    public fun reroll(
        owner: PlayerId,
        wallet: Wallet,
        instanceId: ItemInstanceId,
        keepIfBetter: (previous: List<SubStatRoll>, candidate: List<SubStatRoll>) -> Boolean = KEEP_IF_HIGHER_SUM,
    ): Result<Outcome, RefinementError> {
        // 1. Load the instance.
        val instance = instances.findById(instanceId)?.takeIf { it.owner == owner }
            ?: return Result.failure(RefinementError.UnknownInstance(instanceId))

        // 2. Re-derive the per-item spec.
        items.find(instance.itemId) ?: return Result.failure(RefinementError.UnknownItem(instance.itemId))
        val spec = refinementSpecResolver(instance.itemId)
        if (!spec.enabled || spec.subStatCount == 0) {
            return Result.failure(RefinementError.NotRefinable(instance.itemId))
        }

        // 3. Charge cost (escalating ladder).
        val cost = RefinementMath.rerollCost(instance.rerollCount())
        val withdraw = wallet.withdraw(rerollCurrency, cost)
        if (withdraw.isFailure) {
            val err = (withdraw.errorOrNull() as WalletError.InsufficientFunds)
            return Result.failure(
                RefinementError.InsufficientFunds(
                    currency = err.currency,
                    have = err.have,
                    tried = err.tried,
                ),
            )
        }

        // 4. Roll the candidate set and decide keep / discard.
        val previous = instance.subStatRolls()
        val candidate = randomizer.rollSubStats(spec)
        val kept = keepIfBetter(previous, candidate)
        val rerollIndexBefore = instance.rerollCount()
        if (kept) {
            instance.replaceSubStats(candidate)
        } else {
            instance.bumpRerollCount()
        }
        instances.save(instance)

        // 5. Publish — always (analytics needs the attempt rate per ADR-0100 §Mitigations).
        eventBus.publish(
            ItemInstanceRerolled(
                instanceUuid = instance.id.value,
                itemId = instance.itemId,
                owner = owner,
                keptResult = kept,
                rerollIndex = rerollIndexBefore,
            ),
        )

        return Result.success(
            Outcome(
                instance = instance,
                previous = previous,
                candidate = candidate,
                kept = kept,
                costPaid = cost,
            ),
        )
    }
}
