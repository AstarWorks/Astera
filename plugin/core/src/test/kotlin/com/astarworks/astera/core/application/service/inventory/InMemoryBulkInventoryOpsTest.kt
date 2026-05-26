package com.astarworks.astera.core.application.service.inventory

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.InventoryTransferred
import com.astarworks.astera.core.domain.inventory.InventoryTransferTarget
import com.astarworks.astera.core.domain.inventory.KitError
import com.astarworks.astera.core.domain.inventory.KitId
import com.astarworks.astera.core.domain.inventory.LootError
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryBulkInventoryOpsTest {

    private val player = PlayerId(UUID.randomUUID())
    private val apple = ItemId("apple")
    private val sword = ItemId("sword")

    private fun fixture(
        lootTables: Map<LootTableId, Map<ItemId, Int>> = emptyMap(),
        kits: Map<KitId, Map<ItemId, Int>> = emptyMap(),
    ): Triple<InMemoryBulkInventoryOps, InMemoryInventoryMutator, InMemoryDomainEventBus> {
        val mutator = InMemoryInventoryMutator()
        val bus = InMemoryDomainEventBus()
        val bulk = InMemoryBulkInventoryOps(
            mutator = mutator,
            eventBus = bus,
            lootTableResolver = { lootTables[it] },
            kitResolver = { kits[it] },
            tickProvider = { 100L },
        )
        return Triple(bulk, mutator, bus)
    }

    @Test
    fun `applyLootTable delivers all items on success and fires InventoryTransferred`() {
        val table = LootTableId("astera:forest")
        val drops = mapOf(apple to 3, sword to 1)
        val (bulk, mutator, bus) = fixture(lootTables = mapOf(table to drops))
        var captured: InventoryTransferred? = null
        bus.subscribe(InventoryTransferred::class) { captured = it }

        val r = bulk.applyLootTable(player, table)

        assertThat(r).isInstanceOf(Result.Success::class.java)
        val applied = (r as Result.Success).value
        assertThat(applied.lootTableId).isEqualTo(table)
        assertThat(applied.deliveredItems).isEqualTo(drops)
        assertThat(applied.leftoverItems).isEmpty()
        assertThat(mutator.count(player, apple)).isEqualTo(3)
        assertThat(mutator.count(player, sword)).isEqualTo(1)
        assertThat(captured).isNotNull
        val transferred = captured!!
        assertThat(transferred.fromPlayer).isNull()
        assertThat(transferred.target)
            .isEqualTo(InventoryTransferTarget.PlayerInventory(player))
        assertThat(transferred.atTick).isEqualTo(100L)
    }

    @Test
    fun `applyLootTable returns UnknownLootTable on miss`() {
        val (bulk, _, _) = fixture()
        val r = bulk.applyLootTable(player, LootTableId("astera:nope"))
        assertThat((r as Result.Failure).error)
            .isInstanceOf(LootError.UnknownLootTable::class.java)
    }

    @Test
    fun `applyLootTable with empty table is success and fires no event`() {
        val table = LootTableId("astera:empty")
        val (bulk, _, bus) = fixture(lootTables = mapOf(table to emptyMap()))
        var fired = 0
        bus.subscribe(InventoryTransferred::class) { fired++ }
        val r = bulk.applyLootTable(player, table)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        assertThat(fired).isEqualTo(0)
    }

    @Test
    fun `issueKit delivers all items and fires InventoryTransferred`() {
        val kit = KitId("astera:pvp-starter")
        val items = mapOf(sword to 1, apple to 16)
        val (bulk, mutator, bus) = fixture(kits = mapOf(kit to items))
        var captured: InventoryTransferred? = null
        bus.subscribe(InventoryTransferred::class) { captured = it }

        val r = bulk.issueKit(player, kit)

        assertThat(r).isInstanceOf(Result.Success::class.java)
        assertThat(mutator.count(player, sword)).isEqualTo(1)
        assertThat(mutator.count(player, apple)).isEqualTo(16)
        val transferred = captured!!
        assertThat(transferred.items).isEqualTo(items)
    }

    @Test
    fun `issueKit returns UnknownKit on miss`() {
        val (bulk, _, _) = fixture()
        val r = bulk.issueKit(player, KitId("astera:nope"))
        assertThat((r as Result.Failure).error)
            .isInstanceOf(KitError.UnknownKit::class.java)
    }
}
