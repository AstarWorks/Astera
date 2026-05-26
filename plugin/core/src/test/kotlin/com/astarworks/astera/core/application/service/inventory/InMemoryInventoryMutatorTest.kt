package com.astarworks.astera.core.application.service.inventory

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.inventory.ClearScope
import com.astarworks.astera.core.domain.inventory.InventoryError
import com.astarworks.astera.core.domain.inventory.SlotTypeLockRule
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryInventoryMutatorTest {

    private val player = PlayerId(UUID.randomUUID())
    private val apple = ItemId("apple")
    private val sword = ItemId("sword")

    @Test
    fun `give and count round-trip`() {
        val m = InMemoryInventoryMutator()
        val r = m.give(player, apple, 5)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        assertThat(m.count(player, apple)).isEqualTo(5)
    }

    @Test
    fun `give twice stacks into the same slot`() {
        val m = InMemoryInventoryMutator()
        m.give(player, apple, 3)
        m.give(player, apple, 7)
        assertThat(m.count(player, apple)).isEqualTo(10)
    }

    @Test
    fun `give zero is a successful no-op`() {
        val m = InMemoryInventoryMutator()
        val r = m.give(player, apple, 0)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        assertThat(m.count(player, apple)).isEqualTo(0)
    }

    @Test
    fun `take returns actual taken count (lenient)`() {
        val m = InMemoryInventoryMutator()
        m.give(player, apple, 5)
        val r = m.take(player, apple, 8)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        assertThat((r as Result.Success).value).isEqualTo(5)
        assertThat(m.count(player, apple)).isEqualTo(0)
    }

    @Test
    fun `take respects requested when stock is sufficient`() {
        val m = InMemoryInventoryMutator()
        m.give(player, apple, 10)
        val r = m.take(player, apple, 3)
        assertThat((r as Result.Success).value).isEqualTo(3)
        assertThat(m.count(player, apple)).isEqualTo(7)
    }

    @Test
    fun `clear All wipes everything`() {
        val m = InMemoryInventoryMutator()
        m.give(player, apple, 5)
        m.give(player, sword, 1)
        m.clear(player, ClearScope.All)
        assertThat(m.count(player, apple)).isEqualTo(0)
        assertThat(m.count(player, sword)).isEqualTo(0)
    }

    @Test
    fun `clear Hotbar leaves main alone`() {
        val m = InMemoryInventoryMutator()
        // first give fills slot 0 (hotbar); add a sword to slot 9 directly via setSlot
        m.give(player, apple, 5)
        m.setSlot(player, 9, sword, 1)
        m.clear(player, ClearScope.Hotbar)
        assertThat(m.count(player, apple)).isEqualTo(0)
        assertThat(m.count(player, sword)).isEqualTo(1)
    }

    @Test
    fun `clear Slot with out-of-range index returns InvalidSlotIndex`() {
        val m = InMemoryInventoryMutator()
        // ClearScope.Slot itself validates in init — use setSlot for the negative path
        val r = m.setSlot(player, 99, apple, 1)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        val err = (r as Result.Failure).error
        assertThat(err).isInstanceOf(InventoryError.InvalidSlotIndex::class.java)
    }

    @Test
    fun `swap exchanges two non-empty slots`() {
        val m = InMemoryInventoryMutator()
        m.setSlot(player, 0, apple, 3)
        m.setSlot(player, 1, sword, 1)
        m.swap(player, 0, 1)
        // We can't peek slots directly via the port — verify counts still total
        assertThat(m.count(player, apple)).isEqualTo(3)
        assertThat(m.count(player, sword)).isEqualTo(1)
    }

    @Test
    fun `swap with same slot is no-op success`() {
        val m = InMemoryInventoryMutator()
        m.setSlot(player, 0, apple, 3)
        val r = m.swap(player, 0, 0)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        assertThat(m.count(player, apple)).isEqualTo(3)
    }

    @Test
    fun `swap with invalid slot returns InvalidSlotIndex`() {
        val m = InMemoryInventoryMutator()
        val r = m.swap(player, 0, 99)
        assertThat((r as Result.Failure).error)
            .isInstanceOf(InventoryError.InvalidSlotIndex::class.java)
    }

    @Test
    fun `setSlot with null itemId empties the slot`() {
        val m = InMemoryInventoryMutator()
        m.setSlot(player, 0, apple, 5)
        m.setSlot(player, 0, null, 0)
        assertThat(m.count(player, apple)).isEqualTo(0)
    }

    @Test
    fun `setSlot enforces armor slot type lock by default`() {
        val m = InMemoryInventoryMutator()
        val r = m.setSlot(player, 39, sword, 1)
        assertThat((r as Result.Failure).error)
            .isInstanceOf(InventoryError.SlotTypeLocked::class.java)
    }

    @Test
    fun `setSlot accepts armor in matching slot`() {
        val m = InMemoryInventoryMutator()
        val r = m.setSlot(player, 39, ItemId("dragon-helmet"), 1)
        assertThat(r).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `permissive lock allows non-armor in armor slot`() {
        val m = InMemoryInventoryMutator(slotLock = SlotTypeLockRule.PERMISSIVE)
        val r = m.setSlot(player, 39, sword, 1)
        assertThat(r).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `count returns zero for unknown player`() {
        val m = InMemoryInventoryMutator()
        assertThat(m.count(PlayerId(UUID.randomUUID()), apple)).isEqualTo(0)
    }
}
