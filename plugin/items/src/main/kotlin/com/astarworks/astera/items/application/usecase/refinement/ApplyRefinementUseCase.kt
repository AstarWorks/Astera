package com.astarworks.astera.items.application.usecase.refinement

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.ItemInstanceRefined
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.port.outbound.IItemInstanceRepository
import com.astarworks.astera.items.domain.refinement.ItemInstance
import com.astarworks.astera.items.domain.refinement.ItemInstanceId
import com.astarworks.astera.items.domain.refinement.Refinement
import com.astarworks.astera.items.domain.refinement.RefinementError
import com.astarworks.astera.items.domain.refinement.RefinementSpec
import java.time.Clock
import java.util.UUID

/**
 * Apply a [Refinement] layer to an [ItemInstance] (Wave 5refine, ADR-0100).
 *
 * Lifecycle:
 *
 *   1. Resolve the [ItemId] → [com.astarworks.astera.core.domain.item.ItemSpec]
 *      via [items]; fail with [RefinementError.UnknownItem] if missing.
 *   2. Read the per-item refinement profile via [refinementSpecResolver]; fail
 *      with [RefinementError.NotRefinable] if disabled / fully empty.
 *   3. Either load the existing instance via [instances] or create a fresh
 *      one (auto-promote on first application).
 *   4. Validate the layer against the spec (gemstone slot bound, reforge pool
 *      entry, …) — fail with [RefinementError.LayerNotAllowed] on mismatch.
 *   5. Mutate via `instance.addLayer(layer)`; persist via `instances.save`.
 *   6. Publish [ItemInstanceRefined] for downstream tracks.
 *
 * # Currency cost is NOT charged here
 *
 * ADR-0100 §"Resource sink" gates the cost at the *gating UI* (Wave 6 inspect
 * GUI) — this use case is the atomic "apply the layer" step. The Wave 6 GUI
 * wraps a Wallet.withdraw() + this use case in a single transaction so a
 * failed cost path never produces a half-applied instance. Keeping cost
 * outside the domain step keeps it pure and testable in isolation. Reroll
 * cost *is* domain-relevant — see [RerollSubStatsUseCase].
 */
public class ApplyRefinementUseCase(
    private val items: IItemRegistry,
    private val instances: IItemInstanceRepository,
    private val eventBus: IDomainEventBus,
    private val refinementSpecResolver: (ItemId) -> RefinementSpec,
    private val clock: Clock = Clock.systemUTC(),
) {

    public data class Outcome(
        public val instance: ItemInstance,
        public val totalLayers: Int,
    )

    public suspend fun apply(
        owner: PlayerId,
        instanceId: ItemInstanceId?,
        itemId: ItemId,
        layer: Refinement,
    ): Result<Outcome, RefinementError> {
        // (1) resolve template
        items.find(itemId) ?: return Result.failure(RefinementError.UnknownItem(itemId))

        // (2) read refinement profile
        val spec = refinementSpecResolver(itemId)
        if (!spec.enabled || !spec.anyAxisEnabled) {
            return Result.failure(RefinementError.NotRefinable(itemId))
        }

        // (3) load-or-create the instance
        val instance: ItemInstance = if (instanceId != null) {
            instances.findById(instanceId)?.takeIf { it.owner == owner && it.itemId == itemId }
                ?: return Result.failure(
                RefinementError.UnknownInstance(instanceId),
            )
        } else {
            ItemInstance(
                id = ItemInstanceId(UUID.randomUUID()),
                itemId = itemId,
                owner = owner,
                createdAt = clock.instant(),
            )
        }

        // (4) layer validation against spec
        validateLayerAgainstSpec(layer, spec, itemId)?.let { return Result.failure(it) }

        // (5) mutate + persist
        val newTotal = instance.addLayer(layer)
        instances.save(instance)

        // (6) publish
        eventBus.publish(
            ItemInstanceRefined(
                instanceUuid = instance.id.value,
                itemId = itemId,
                owner = owner,
                layerKind = layerKind(layer),
                totalLayers = newTotal,
            ),
        )

        return Result.success(Outcome(instance = instance, totalLayers = newTotal))
    }

    private fun validateLayerAgainstSpec(
        layer: Refinement,
        spec: RefinementSpec,
        itemId: ItemId,
    ): RefinementError.LayerNotAllowed? = when (layer) {
        is Refinement.Gemstone -> {
            if (spec.gemstoneSlots == 0) {
                RefinementError.LayerNotAllowed(itemId, "no gemstone slots on this item")
            } else if (layer.slotIndex >= spec.gemstoneSlots) {
                RefinementError.LayerNotAllowed(
                    itemId,
                    "gemstone slot ${layer.slotIndex} >= max ${spec.gemstoneSlots}",
                )
            } else {
                null
            }
        }
        is Refinement.Reforge -> {
            if (spec.reforgePool.isEmpty()) {
                RefinementError.LayerNotAllowed(itemId, "no reforge pool on this item")
            } else if (layer.poolEntry !in spec.reforgePool) {
                RefinementError.LayerNotAllowed(
                    itemId,
                    "reforge poolEntry '${layer.poolEntry}' not in ${spec.reforgePool}",
                )
            } else {
                null
            }
        }
        is Refinement.Star -> {
            if (spec.upgradeableTo == 0) {
                RefinementError.LayerNotAllowed(itemId, "this item is not star-upgradable")
            } else if (layer.stars > spec.upgradeableTo) {
                RefinementError.LayerNotAllowed(
                    itemId,
                    "star ${layer.stars} > max ${spec.upgradeableTo}",
                )
            } else {
                null
            }
        }
        is Refinement.Enchant, is Refinement.Recomb -> null
    }

    private fun layerKind(layer: Refinement): String = when (layer) {
        is Refinement.Reforge -> "reforge"
        is Refinement.Enchant -> "enchant"
        is Refinement.Gemstone -> "gemstone"
        is Refinement.Star -> "star"
        is Refinement.Recomb -> "recomb"
    }
}
