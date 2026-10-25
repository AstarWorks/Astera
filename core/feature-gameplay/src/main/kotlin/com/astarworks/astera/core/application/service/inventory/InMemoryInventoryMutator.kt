package com.astarworks.astera.core.application.service.inventory

import com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.inventory.ClearScope
import com.astarworks.astera.core.domain.inventory.DefaultSlotTypeLockRule
import com.astarworks.astera.core.domain.inventory.InventoryError
import com.astarworks.astera.core.domain.inventory.SlotTypeLockRule
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Phase 1 in-memory implementation of [IInventoryMutator]
 * (Wave 5world-events, ADR-0111).
 *
 * Backs each player with a fixed-size `Array<Slot?>` (41 slots: 0..8 hotbar
 * + 9..35 main + 36..39 armor + 40 off-hand) so use-case tests can exercise
 * the full set of operations without booting Paper. The Wave 6 Paper adapter
 * (`PaperInventoryMutator`) will replace this at runtime via the standard
 * `ServiceRegistry` priority-override pattern.
 *
 * The "give" path follows the canonical fill-order:
 *
 *  1. Top up existing matching stacks in main + hotbar (no max-stack-size
 *     handling — see note below).
 *  2. Drop into the first empty main / hotbar slot.
 *  3. If still leftover, return [InventoryError.InventoryFull] with the count.
 *
 * Max stack size is not modeled in Phase 1 — every slot can hold any count.
 * The Paper adapter enforces Bukkit's per-material stack limits. Tests +
 * use-case code that needs strict stack semantics can build on top of this
 * by counting before giving.
 *
 * @property slotLock Pluggable slot-type lock. Defaults to
 *   [DefaultSlotTypeLockRule] (Minecraft armor-slot semantics).
 */
public class InMemoryInventoryMutator(
    private val slotLock: SlotTypeLockRule = DefaultSlotTypeLockRule,
) : IInventoryMutator {

    private data class Slot(val itemId: ItemId, val count: Int)

    private val inventories: MutableMap<PlayerId, Array<Slot?>> = mutableMapOf()

    private companion object {
        const val SLOT_COUNT = 41
        const val HOTBAR_END_EXCLUSIVE = 9
        const val MAIN_END_EXCLUSIVE = 36
        const val ARMOR_END_EXCLUSIVE = 40
        const val OFFHAND_SLOT = 40
    }

    private fun slotsFor(playerId: PlayerId): Array<Slot?> =
        inventories.getOrPut(playerId) { arrayOfNulls(SLOT_COUNT) }

    override fun give(playerId: PlayerId, itemId: ItemId, count: Int): Result<Unit, InventoryError> {
        if (count <= 0) return Result.success(Unit)
        val slots = slotsFor(playerId)
        var remaining = count
        // Step 1: top up existing same-item stacks in main + hotbar (0..35).
        for (i in 0 until MAIN_END_EXCLUSIVE) {
            if (remaining <= 0) break
            val s = slots[i] ?: continue
            if (s.itemId == itemId) {
                slots[i] = s.copy(count = s.count + remaining)
                remaining = 0
            }
        }
        // Step 2: fill empty slots in main + hotbar (0..35).
        for (i in 0 until MAIN_END_EXCLUSIVE) {
            if (remaining <= 0) break
            if (slots[i] == null) {
                slots[i] = Slot(itemId, remaining)
                remaining = 0
            }
        }
        return if (remaining == 0) {
            Result.success(Unit)
        } else {
            Result.failure(InventoryError.InventoryFull(playerId, itemId, remaining))
        }
    }

    override fun take(playerId: PlayerId, itemId: ItemId, count: Int): Result<Int, InventoryError> {
        if (count <= 0) return Result.success(0)
        val slots = slotsFor(playerId)
        var remaining = count
        var taken = 0
        // Walk every addressable slot (0..40) so off-hand stacks also drain.
        for (i in 0 until SLOT_COUNT) {
            if (remaining <= 0) break
            val s = slots[i] ?: continue
            if (s.itemId != itemId) continue
            val pull = minOf(s.count, remaining)
            taken += pull
            remaining -= pull
            slots[i] = if (s.count == pull) null else s.copy(count = s.count - pull)
        }
        return Result.success(taken)
    }

    override fun clear(playerId: PlayerId, scope: ClearScope): Result<Unit, InventoryError> {
        val slots = slotsFor(playerId)
        val range: IntRange = when (scope) {
            ClearScope.All -> 0..OFFHAND_SLOT
            ClearScope.Hotbar -> 0 until HOTBAR_END_EXCLUSIVE
            ClearScope.MainInventory -> HOTBAR_END_EXCLUSIVE until MAIN_END_EXCLUSIVE
            ClearScope.Armor -> MAIN_END_EXCLUSIVE until ARMOR_END_EXCLUSIVE
            ClearScope.OffHand -> OFFHAND_SLOT..OFFHAND_SLOT
            is ClearScope.Slot -> {
                if (scope.slotIndex !in 0..OFFHAND_SLOT) {
                    return Result.failure(InventoryError.InvalidSlotIndex(scope.slotIndex))
                }
                scope.slotIndex..scope.slotIndex
            }
        }
        for (i in range) slots[i] = null
        return Result.success(Unit)
    }

    override fun swap(playerId: PlayerId, slotA: Int, slotB: Int): Result<Unit, InventoryError> {
        if (slotA !in 0..OFFHAND_SLOT) return Result.failure(InventoryError.InvalidSlotIndex(slotA))
        if (slotB !in 0..OFFHAND_SLOT) return Result.failure(InventoryError.InvalidSlotIndex(slotB))
        if (slotA == slotB) return Result.success(Unit)
        val slots = slotsFor(playerId)
        val tmp = slots[slotA]
        slots[slotA] = slots[slotB]
        slots[slotB] = tmp
        return Result.success(Unit)
    }

    override fun setSlot(
        playerId: PlayerId,
        slotIndex: Int,
        itemId: ItemId?,
        count: Int,
    ): Result<Unit, InventoryError> {
        if (slotIndex !in 0..OFFHAND_SLOT) {
            return Result.failure(InventoryError.InvalidSlotIndex(slotIndex))
        }
        val slots = slotsFor(playerId)
        if (itemId == null || count <= 0) {
            slots[slotIndex] = null
            return Result.success(Unit)
        }
        if (!slotLock.allows(slotIndex, itemId)) {
            return Result.failure(InventoryError.SlotTypeLocked(slotIndex, itemId))
        }
        slots[slotIndex] = Slot(itemId, count)
        return Result.success(Unit)
    }

    override fun count(playerId: PlayerId, itemId: ItemId): Int {
        val slots = inventories[playerId] ?: return 0
        var total = 0
        for (s in slots) {
            if (s != null && s.itemId == itemId) total += s.count
        }
        return total
    }
}
