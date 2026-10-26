package com.astarworks.astera.core.application.service.inventory

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.inventory.IBulkInventoryOps
import com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.InventoryTransferred
import com.astarworks.astera.core.domain.inventory.InventoryError
import com.astarworks.astera.core.domain.inventory.InventoryTransferTarget
import com.astarworks.astera.core.domain.inventory.KitError
import com.astarworks.astera.core.domain.inventory.KitId
import com.astarworks.astera.core.domain.inventory.LootApplied
import com.astarworks.astera.core.domain.inventory.LootError
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Phase 1 in-memory implementation of [IBulkInventoryOps]
 * (Wave 5world-events, ADR-0111).
 *
 * Delegates the actual stack-shuffling to an injected [IInventoryMutator]
 * (Phase 1 default: [InMemoryInventoryMutator]; Wave 6 swap-in: Paper
 * adapter via `ServiceRegistry` priority override) and fires a single
 * [InventoryTransferred] event per successful bulk operation.
 *
 * Loot table + kit lookups are injected as plain lambdas
 * ([lootTableResolver] / [kitResolver]) rather than dedicated registry
 * ports — keeping the port surface lean while staying drop-in replaceable
 * once Wave 5pve-content registry / Wave 5e kit registry land.
 *
 * # Tick provenance
 *
 * The [tickProvider] lambda yields the current server tick for the
 * [InventoryTransferred] event payload. In tests it can be a frozen `{ 0L }`;
 * the production wiring (Wave 6) passes `IScheduler.currentTick`.
 */
public class InMemoryBulkInventoryOps(
    private val mutator: IInventoryMutator,
    private val eventBus: IDomainEventBus,
    private val lootTableResolver: (LootTableId) -> Map<ItemId, Int>?,
    private val kitResolver: (KitId) -> Map<ItemId, Int>?,
    private val tickProvider: () -> Long = { 0L },
) : IBulkInventoryOps {

    override fun applyLootTable(
        playerId: PlayerId,
        lootTableId: LootTableId,
    ): Result<LootApplied, LootError> {
        val drops = lootTableResolver(lootTableId)
            ?: return Result.failure(LootError.UnknownLootTable(lootTableId))
        val delivered = mutableMapOf<ItemId, Int>()
        val leftover = mutableMapOf<ItemId, Int>()
        for ((itemId, count) in drops) {
            if (count <= 0) continue
            when (val giveResult = mutator.give(playerId, itemId, count)) {
                is Result.Success -> {
                    delivered.merge(itemId, count) { a, b -> a + b }
                }
                is Result.Failure -> {
                    val err = giveResult.error
                    if (err is InventoryError.InventoryFull && err.itemId == itemId) {
                        val placed = count - err.leftover
                        if (placed > 0) delivered.merge(itemId, placed) { a, b -> a + b }
                        leftover.merge(itemId, err.leftover) { a, b -> a + b }
                    } else {
                        return Result.failure(LootError.DeliveryFailed(err))
                    }
                }
            }
        }
        if (delivered.isNotEmpty()) {
            eventBus.publish(
                InventoryTransferred(
                    fromPlayer = null,
                    target = InventoryTransferTarget.PlayerInventory(playerId),
                    items = delivered.toMap(),
                    atTick = tickProvider(),
                ),
            )
        }
        return Result.success(LootApplied(lootTableId, delivered.toMap(), leftover.toMap()))
    }

    override fun issueKit(playerId: PlayerId, kitId: KitId): Result<Unit, KitError> {
        val items = kitResolver(kitId)
            ?: return Result.failure(KitError.UnknownKit(kitId))
        val delivered = mutableMapOf<ItemId, Int>()
        for ((itemId, count) in items) {
            if (count <= 0) continue
            when (val giveResult = mutator.give(playerId, itemId, count)) {
                is Result.Success -> {
                    delivered.merge(itemId, count) { a, b -> a + b }
                }
                is Result.Failure -> return Result.failure(KitError.DeliveryFailed(giveResult.error))
            }
        }
        if (delivered.isNotEmpty()) {
            eventBus.publish(
                InventoryTransferred(
                    fromPlayer = null,
                    target = InventoryTransferTarget.PlayerInventory(playerId),
                    items = delivered.toMap(),
                    atTick = tickProvider(),
                ),
            )
        }
        return Result.success(Unit)
    }
}
