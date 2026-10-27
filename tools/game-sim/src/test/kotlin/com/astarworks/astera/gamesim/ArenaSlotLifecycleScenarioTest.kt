package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.service.instance.ArenaSlotBook
import com.astarworks.astera.core.application.service.instance.SlotAllocator
import com.astarworks.astera.core.domain.stage.StageId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Item 24: the arena slot lifecycle (warm copy -> claimed by a match -> rolled back -> slot freed -> next copy) lost
 * slots or built two arenas into one slot. These scenarios drive the same [ArenaSlotBook] the Paper arena world uses,
 * through the sequences that broke it.
 */
class ArenaSlotLifecycleScenarioTest {
    private val template = StageId("example-arena")
    private val slots = SlotAllocator()
    private val book = ArenaSlotBook<String>(slots, maxFailures = 2)

    private fun warmCopy(label: String): Pair<StageId, ArenaSlotBook.Entry<String>> {
        val slot = checkNotNull(slots.allocate(template))
        val id = StageId("${template.value}-slot-${slot.index}")
        val entry = ArenaSlotBook.Entry(slot, label)
        book.publish(id, entry, template)
        return id to book.build(id)!!
    }

    @Test
    fun `a duplicate repair request cannot free the slot of a newer build`() {
        val (id, first) = warmCopy("match-1 arena")
        book.claim(first)
        val claim = checkNotNull(book.beginRepair(id)) { "the played arena must be repairable" }
        assertThat(book.beginRepair(id)).`as`("second request while the repair runs is ignored").isNull()

        book.finishRepair(id, claim)
        // The freed slot is handed to the next warm copy (same index, same placed id).
        val (id2, second) = warmCopy("match-2 arena")
        assertThat(id2).isEqualTo(id)
        // A stale/duplicate request from match 1 arrives now: the pristine Ready copy must survive.
        assertThat(book.beginRepair(id)).`as`("stale repair must not tear down a Ready copy").isNull()
        assertThat(book.ready(template)?.build).isEqualTo("match-2 arena")
        assertThat(slots.snapshot().single { it.index == second.slot.index }.state).isEqualTo(SlotAllocator.State.Ready)
    }

    @Test
    fun `a finished repair frees the slot and a replacement can be built`() {
        val (id, entry) = warmCopy("arena")
        book.claim(entry)
        assertThat(book.ready(template)).`as`("a claimed copy is not claimable twice").isNull()
        val repair = checkNotNull(book.beginRepair(id))
        book.finishRepair(id, repair)
        assertThat(book.contains(id)).isFalse()
        assertThat(slots.snapshot().none { it.index == entry.slot.index }).isTrue()
        assertThat(book.waiting(template)).isZero()
    }

    @Test
    fun `repair failures are budgeted but a success resets the budget`() {
        val (id, entry) = warmCopy("arena")
        book.claim(entry)
        repeat(2) {
            checkNotNull(book.beginRepair(id))
            assertThat(book.repairFailed(id)).`as`("attempt ${it + 1} may retry").isTrue()
        }
        // A success (re-paste) clears the budget so later hiccups of the same arena are retried again.
        checkNotNull(book.beginRepair(id)).let { book.repasted(id, it, "repasted") }
        assertThat(book.ready(template)?.build).isEqualTo("repasted")
        book.claim(book.build(id)!!)
        repeat(2) {
            checkNotNull(book.beginRepair(id))
            assertThat(book.repairFailed(id)).isTrue()
        }
        checkNotNull(book.beginRepair(id))
        assertThat(book.repairFailed(id)).`as`("budget exhausted only after consecutive failures").isFalse()
    }

    @Test
    fun `a failed paste is quarantined then released and never reused dirty`() {
        val slot = checkNotNull(slots.allocate(template))
        book.quarantine(slot)
        assertThat(book.waiting(template)).isZero()
        assertThat(book.ready(template)).isNull()
        assertThat(slots.allocate(template)!!.index).`as`("quarantined slot is not handed out").isNotEqualTo(slot.index)
        assertThat(book.release(slot)).isTrue()
        assertThat(book.release(slot)).`as`("second release is a no-op").isFalse()
        assertThat(slots.allocate(template)!!.index).`as`("released slot is reusable").isEqualTo(slot.index)
    }

    @Test
    fun `a build whose paste never happened gives its slot back`() {
        val slot = checkNotNull(slots.allocate(template))
        book.abandon(slot)
        assertThat(slots.snapshot().none { it.index == slot.index }).isTrue()
    }
}
