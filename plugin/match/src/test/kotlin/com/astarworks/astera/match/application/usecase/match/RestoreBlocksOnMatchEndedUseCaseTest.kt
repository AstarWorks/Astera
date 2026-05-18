package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.BlockPlaced
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.BlockChangeJournal
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.domain.match.MatchWorld
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5d [RestoreBlocksOnMatchEndedUseCase] tests.
 *
 * Pins:
 *  - Happy path: MatchWorld registered + journal seeded with Placed + Broken
 *    → MatchEnded triggers one `gateway.restore(world, pos)` per journal
 *    entry in **reverse** order. Journal is cleared afterwards.
 *  - No MatchWorld: use case logs and skips gateway calls, but still
 *    clears the journal (idempotent).
 *  - Empty journal: MatchWorld present but no journal entries → no gateway
 *    calls, journal cleared.
 *  - Sibling matches: ending match A does not consume match B's journal
 *    nor restore in B's world.
 *
 * Test doubles are inline anonymous objects so this test does not depend
 * on Worker α (`InMemoryBlockStateGateway` in `plugin/core`) or Worker ζ's
 * production [com.astarworks.astera.match.application.service.InMemoryMatchWorldRepository]
 * landing in this same wave (both exist today, but the in-line fakes keep
 * scope obvious and Phase B independent).
 */
internal class RestoreBlocksOnMatchEndedUseCaseTest {

    private val matchId = MatchId("restore-test")
    private val otherMatchId = MatchId("restore-other")
    private val worldId = WorldId("match-restore-test")
    private val otherWorldId = WorldId("match-restore-other")
    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @Test
    fun `restores every journaled position in reverse order then clears the journal`() {
        val gateway = RecordingGateway()
        val matchWorlds = InMemoryMatchWorlds()
        val journal = BlockChangeJournal()
        val bus = FakeDomainEventBus()

        matchWorlds.save(
            MatchWorld(
                matchId = matchId,
                worldId = worldId,
                sourceSchematicPath = "schematics/test.schem",
            ),
        )

        val useCase = RestoreBlocksOnMatchEndedUseCase(gateway, journal, matchWorlds)
        journal.subscribe(bus)
        useCase.subscribe(bus)

        // Seed the journal: Placed @1 then Broken @2.
        bus.publish(placed(Vec3(1.0, 64.0, 1.0), "minecraft:stone", tick = 1L))
        bus.publish(broken(Vec3(2.0, 64.0, 2.0), "minecraft:dirt", tick = 2L))
        assertThat(journal.snapshot(matchId)).hasSize(2)

        bus.publish(matchEnded(tick = 99L))

        // Reverse-replay: the most recent (Broken @2, pos=(2,64,2)) restores
        // first, then the earlier (Placed @1, pos=(1,64,1)).
        assertThat(gateway.restoreCalls).containsExactly(
            RestoreCall(worldId, Vec3(2.0, 64.0, 2.0)),
            RestoreCall(worldId, Vec3(1.0, 64.0, 1.0)),
        )
        assertThat(journal.snapshot(matchId)).isEmpty()
    }

    @Test
    fun `no MatchWorld means no restore calls but journal is still cleared`() {
        val gateway = RecordingGateway()
        val matchWorlds = InMemoryMatchWorlds()
        val journal = BlockChangeJournal()
        val bus = FakeDomainEventBus()

        // Intentionally do NOT save a MatchWorld.
        val useCase = RestoreBlocksOnMatchEndedUseCase(gateway, journal, matchWorlds)
        journal.subscribe(bus)
        useCase.subscribe(bus)

        bus.publish(placed(Vec3(1.0, 64.0, 1.0), "minecraft:stone"))
        assertThat(journal.snapshot(matchId)).hasSize(1)

        bus.publish(matchEnded())

        assertThat(gateway.restoreCalls).isEmpty()
        assertThat(journal.snapshot(matchId)).isEmpty()
    }

    @Test
    fun `empty journal with a MatchWorld present is a no-op restore that still clears`() {
        val gateway = RecordingGateway()
        val matchWorlds = InMemoryMatchWorlds()
        val journal = BlockChangeJournal()
        val bus = FakeDomainEventBus()

        matchWorlds.save(
            MatchWorld(
                matchId = matchId,
                worldId = worldId,
                sourceSchematicPath = "schematics/test.schem",
            ),
        )

        val useCase = RestoreBlocksOnMatchEndedUseCase(gateway, journal, matchWorlds)
        useCase.subscribe(bus)

        bus.publish(matchEnded())

        assertThat(gateway.restoreCalls).isEmpty()
        assertThat(journal.snapshot(matchId)).isEmpty()
    }

    @Test
    fun `ending match A does not touch match B's journal or restore in B's world`() {
        val gateway = RecordingGateway()
        val matchWorlds = InMemoryMatchWorlds()
        val journal = BlockChangeJournal()
        val bus = FakeDomainEventBus()

        matchWorlds.save(
            MatchWorld(matchId = matchId, worldId = worldId, sourceSchematicPath = "a.schem"),
        )
        matchWorlds.save(
            MatchWorld(matchId = otherMatchId, worldId = otherWorldId, sourceSchematicPath = "b.schem"),
        )

        val useCase = RestoreBlocksOnMatchEndedUseCase(gateway, journal, matchWorlds)
        journal.subscribe(bus)
        useCase.subscribe(bus)

        bus.publish(placed(Vec3(1.0, 64.0, 1.0), "minecraft:stone", matchId = matchId))
        bus.publish(placed(Vec3(9.0, 64.0, 9.0), "minecraft:dirt", matchId = otherMatchId))

        // End only match A.
        bus.publish(matchEnded(matchId = matchId))

        assertThat(gateway.restoreCalls).containsExactly(
            RestoreCall(worldId, Vec3(1.0, 64.0, 1.0)),
        )
        assertThat(journal.snapshot(matchId)).isEmpty()
        assertThat(journal.snapshot(otherMatchId)).hasSize(1)
    }

    // ---- helpers ----

    private fun placed(
        pos: Vec3,
        blockId: String,
        matchId: MatchId = this.matchId,
        tick: Long = 0L,
    ): BlockPlaced = BlockPlaced(
        matchId = matchId,
        playerId = playerId,
        position = pos,
        blockId = blockId,
        atTick = tick,
    )

    private fun broken(
        pos: Vec3,
        blockId: String,
        matchId: MatchId = this.matchId,
        tick: Long = 0L,
    ): BlockBroken = BlockBroken(
        matchId = matchId,
        playerId = playerId,
        position = pos,
        blockId = blockId,
        atTick = tick,
    )

    private fun matchEnded(matchId: MatchId = this.matchId, tick: Long = 0L): MatchEnded =
        MatchEnded(
            matchId = matchId,
            winningTeam = null,
            atTick = tick,
            reason = EndReason.AdminEnded,
        )

    /**
     * Records every `restore(world, pos)` call so tests can assert call
     * count, order, and per-call arguments. Other gateway methods throw —
     * the use case under test must only invoke [restore].
     */
    private class RecordingGateway : IBlockStateGateway {
        val restoreCalls: MutableList<RestoreCall> = mutableListOf()

        override fun getDurability(world: WorldId, pos: Vec3): Int =
            error("RecordingGateway.getDurability not expected in this test")

        override fun applyDamage(world: WorldId, pos: Vec3, amount: Int, tool: ToolType): BlockState =
            error("RecordingGateway.applyDamage not expected in this test")

        override fun restore(world: WorldId, pos: Vec3) {
            restoreCalls += RestoreCall(world, pos)
        }

        override fun track(world: WorldId, pos: Vec3, state: BlockState) =
            error("RecordingGateway.track not expected in this test")

        override fun tracked(world: WorldId): Map<Vec3, BlockState> = emptyMap()
    }

    private data class RestoreCall(val world: WorldId, val pos: Vec3)

    /** Tiny inline [IMatchWorldRepository] — independent of Worker ζ's production impl. */
    private class InMemoryMatchWorlds : IMatchWorldRepository {
        private val store: MutableMap<MatchId, MatchWorld> = mutableMapOf()
        override fun findByMatch(matchId: MatchId): MatchWorld? = store[matchId]
        override fun save(matchWorld: MatchWorld) {
            store[matchWorld.matchId] = matchWorld
        }
        override fun remove(matchId: MatchId) { store.remove(matchId) }
        override fun all(): List<MatchWorld> = store.values.toList()
    }
}
