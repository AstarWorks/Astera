package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.BlockPlaced
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockChange
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5d [BlockChangeJournal] tests.
 *
 * Pins:
 *  - subscribe → publish [BlockPlaced] → snapshot contains a matching
 *    [BlockChange.Placed]; same for [BlockBroken] → [BlockChange.Broken].
 *  - Events for unrelated matches are partitioned correctly.
 *  - `clear(matchId)` empties only that match's journal.
 *  - Cap: publishing [BlockChangeJournal.MAX_PER_MATCH] + 1 events keeps
 *    snapshot at exactly MAX, and the oldest entry is evicted (FIFO).
 *  - Malformed blockId (missing namespace) is logged and dropped — no crash.
 *  - `clear` after eviction resets the "warned" flag so a fresh match
 *    can warn again.
 */
internal class BlockChangeJournalTest {

    private val matchId = MatchId("journal-test")
    private val otherMatchId = MatchId("journal-other")
    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @Test
    fun `subscribe routes BlockPlaced into the journal as Placed`() {
        val bus = FakeDomainEventBus()
        val journal = BlockChangeJournal()
        journal.subscribe(bus)

        bus.publish(placed(pos = Vec3(1.0, 64.0, 2.0), blockId = "minecraft:stone", tick = 10L))

        val snapshot = journal.snapshot(matchId)
        assertThat(snapshot).hasSize(1)
        val entry = snapshot.single()
        assertThat(entry).isInstanceOf(BlockChange.Placed::class.java)
        val placed = entry as BlockChange.Placed
        assertThat(placed.position).isEqualTo(Vec3(1.0, 64.0, 2.0))
        assertThat(placed.blockType).isEqualTo(BlockType("minecraft:stone"))
        assertThat(placed.byPlayer).isEqualTo(playerId)
        assertThat(placed.atTick).isEqualTo(10L)
    }

    @Test
    fun `subscribe routes BlockBroken into the journal as Broken`() {
        val bus = FakeDomainEventBus()
        val journal = BlockChangeJournal()
        journal.subscribe(bus)

        bus.publish(broken(pos = Vec3(5.0, 64.0, 5.0), blockId = "minecraft:dirt", tick = 42L))

        val snapshot = journal.snapshot(matchId)
        assertThat(snapshot).hasSize(1)
        val entry = snapshot.single()
        assertThat(entry).isInstanceOf(BlockChange.Broken::class.java)
        val broken = entry as BlockChange.Broken
        assertThat(broken.position).isEqualTo(Vec3(5.0, 64.0, 5.0))
        assertThat(broken.prevBlockType).isEqualTo(BlockType("minecraft:dirt"))
        assertThat(broken.byPlayer).isEqualTo(playerId)
        assertThat(broken.atTick).isEqualTo(42L)
    }

    @Test
    fun `placed and broken accumulate in insertion order`() {
        val bus = FakeDomainEventBus()
        val journal = BlockChangeJournal()
        journal.subscribe(bus)

        bus.publish(placed(pos = Vec3(0.0, 64.0, 0.0), blockId = "minecraft:stone", tick = 1L))
        bus.publish(broken(pos = Vec3(1.0, 64.0, 0.0), blockId = "minecraft:dirt", tick = 2L))
        bus.publish(placed(pos = Vec3(2.0, 64.0, 0.0), blockId = "minecraft:glass", tick = 3L))

        val snapshot = journal.snapshot(matchId)
        assertThat(snapshot).hasSize(3)
        assertThat(snapshot[0]).isInstanceOf(BlockChange.Placed::class.java)
        assertThat(snapshot[1]).isInstanceOf(BlockChange.Broken::class.java)
        assertThat(snapshot[2]).isInstanceOf(BlockChange.Placed::class.java)
        assertThat(snapshot.map { it.atTick }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `events from different matches are partitioned`() {
        val bus = FakeDomainEventBus()
        val journal = BlockChangeJournal()
        journal.subscribe(bus)

        bus.publish(placed(pos = Vec3(0.0, 64.0, 0.0), blockId = "minecraft:stone"))
        bus.publish(placed(matchId = otherMatchId, pos = Vec3(9.0, 64.0, 9.0), blockId = "minecraft:dirt"))

        assertThat(journal.snapshot(matchId)).hasSize(1)
        assertThat(journal.snapshot(otherMatchId)).hasSize(1)
        assertThat(journal.snapshot(MatchId("unknown"))).isEmpty()
    }

    @Test
    fun `clear empties only the requested match's journal`() {
        val bus = FakeDomainEventBus()
        val journal = BlockChangeJournal()
        journal.subscribe(bus)

        bus.publish(placed(pos = Vec3(0.0, 64.0, 0.0), blockId = "minecraft:stone"))
        bus.publish(placed(matchId = otherMatchId, pos = Vec3(9.0, 64.0, 9.0), blockId = "minecraft:dirt"))

        journal.clear(matchId)

        assertThat(journal.snapshot(matchId)).isEmpty()
        assertThat(journal.snapshot(otherMatchId)).hasSize(1)
    }

    @Test
    fun `cap evicts the oldest entry FIFO once MAX_PER_MATCH is exceeded`() {
        val bus = FakeDomainEventBus()
        val journal = BlockChangeJournal()
        journal.subscribe(bus)

        val cap = BlockChangeJournal.MAX_PER_MATCH
        // Publish MAX + 1 events with monotonically increasing ticks so the
        // eviction target (tick=0) is identifiable.
        for (i in 0..cap) {
            bus.publish(
                placed(
                    pos = Vec3(i.toDouble(), 64.0, 0.0),
                    blockId = "minecraft:stone",
                    tick = i.toLong(),
                ),
            )
        }

        val snapshot = journal.snapshot(matchId)
        assertThat(snapshot).hasSize(cap)
        // Oldest (tick=0) is evicted, so the surviving range starts at tick=1.
        assertThat(snapshot.first().atTick).isEqualTo(1L)
        assertThat(snapshot.last().atTick).isEqualTo(cap.toLong())
    }

    @Test
    fun `invalid blockId is logged and skipped without crashing`() {
        val bus = FakeDomainEventBus()
        val journal = BlockChangeJournal()
        journal.subscribe(bus)

        // No namespace — fails BlockType's validation regex.
        bus.publish(placed(pos = Vec3(0.0, 64.0, 0.0), blockId = "no-namespace"))
        // Valid entry afterwards must still land.
        bus.publish(placed(pos = Vec3(1.0, 64.0, 0.0), blockId = "minecraft:stone"))

        val snapshot = journal.snapshot(matchId)
        assertThat(snapshot).hasSize(1)
        val placed = snapshot.single() as BlockChange.Placed
        assertThat(placed.blockType).isEqualTo(BlockType("minecraft:stone"))
    }

    @Test
    fun `invalid blockId in BlockBroken is also skipped`() {
        val bus = FakeDomainEventBus()
        val journal = BlockChangeJournal()
        journal.subscribe(bus)

        bus.publish(broken(pos = Vec3(0.0, 64.0, 0.0), blockId = "Bad:ID With Spaces"))

        assertThat(journal.snapshot(matchId)).isEmpty()
    }

    @Test
    fun `clear resets the eviction-warned flag so a re-used match can warn again`() {
        val journal = BlockChangeJournal()
        val cap = BlockChangeJournal.MAX_PER_MATCH

        // Drive an eviction on the bare API (no bus needed).
        for (i in 0..cap) {
            journal.onPlaced(placed(pos = Vec3(i.toDouble(), 64.0, 0.0), blockId = "minecraft:stone"))
        }
        assertThat(journal.snapshot(matchId)).hasSize(cap)

        journal.clear(matchId)
        assertThat(journal.snapshot(matchId)).isEmpty()

        // After clear, we should be able to re-fill without surprises.
        journal.onPlaced(placed(pos = Vec3(0.0, 64.0, 0.0), blockId = "minecraft:stone"))
        assertThat(journal.snapshot(matchId)).hasSize(1)
    }

    private fun placed(
        matchId: MatchId = this.matchId,
        pos: Vec3,
        blockId: String,
        tick: Long = 0L,
    ): BlockPlaced = BlockPlaced(
        matchId = matchId,
        playerId = playerId,
        position = pos,
        blockId = blockId,
        atTick = tick,
    )

    private fun broken(
        matchId: MatchId = this.matchId,
        pos: Vec3,
        blockId: String,
        tick: Long = 0L,
    ): BlockBroken = BlockBroken(
        matchId = matchId,
        playerId = playerId,
        position = pos,
        blockId = blockId,
        atTick = tick,
    )
}
