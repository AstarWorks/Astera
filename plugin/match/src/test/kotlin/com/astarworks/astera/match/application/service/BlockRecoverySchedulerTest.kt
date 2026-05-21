package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.service.InMemoryBlockStateGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.usecase.block.BlockStateAutoRecoveryUseCase
import com.astarworks.astera.match.domain.match.MatchWorld
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BlockRecoverySchedulerTest {

    /**
     * Smoke test the scheduler wiring: damage a block at tick 0, arm the
     * scheduler with cycle=200, cool-off=600. The first eligible cycle
     * (tick 800: 200 → 400 → 600 → 800 — 600 is the third fire, but the
     * use case's eligibility gate is `lastDamagedTick + coolOffTicks <=
     * currentTick`, so tick 600 fires and qualifies) regenerates the block.
     */
    @Test
    fun `damage at tick 0, regen begins on the cycle at or after tick 600`() {
        val scheduler = FakeScheduler()
        val gateway = InMemoryBlockStateGateway(initialTick = scheduler::currentTick)
        val world = WorldId("arena-1")
        val pos = Vec3(0.0, 64.0, 0.0)
        val worlds = SingleWorldRepository(
            MatchWorld(
                matchId = MatchId("m1"),
                worldId = world,
                sourceSchematicPath = "noop.schem",
            ),
        )
        val recovery = BlockStateAutoRecoveryUseCase(
            gateway = gateway,
            coolOffTicks = 600L,
            regenPerCycle = 5,
        )
        val sut = BlockRecoveryScheduler(
            matchWorlds = worlds,
            recovery = recovery,
            scheduler = scheduler,
            cyclePeriodTicks = 200L,
        )

        // tick 0: damage. dur = 80.
        gateway.applyDamage(world, pos, amount = 20, tool = ToolType.IRON_PICKAXE)
        val handle = sut.start()

        // First cycle fires at tick 200 (initialDelay = period = 200).
        // Eligibility gate: lastDamagedTick (0) + 600 <= currentTick.
        // At tick 200 → 600 > 200, skipped. dur stays 80.
        scheduler.advanceTicks(200L)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(80)

        // Cycles fire again at 400 → still inside cool-off.
        scheduler.advanceTicks(200L)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(80)

        // Cycle at 600 → 0+600 <= 600 → eligible. dur becomes 85.
        scheduler.advanceTicks(200L)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(85)

        // Cycle at 800 → still eligible (lastDamagedTick unchanged). dur 90.
        scheduler.advanceTicks(200L)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(90)

        handle.cancel()
        assertThat(handle.isActive).isFalse()
    }

    @Test
    fun `cancelling the handle stops further cycles`() {
        val scheduler = FakeScheduler()
        val gateway = InMemoryBlockStateGateway(initialTick = scheduler::currentTick)
        val world = WorldId("arena-1")
        val pos = Vec3(0.0, 64.0, 0.0)
        val worlds = SingleWorldRepository(
            MatchWorld(
                matchId = MatchId("m1"),
                worldId = world,
                sourceSchematicPath = "noop.schem",
            ),
        )
        val recovery = BlockStateAutoRecoveryUseCase(
            gateway = gateway,
            coolOffTicks = 0L, // any tick is eligible — isolates the cancellation behaviour
            regenPerCycle = 1,
        )
        val sut = BlockRecoveryScheduler(
            matchWorlds = worlds,
            recovery = recovery,
            scheduler = scheduler,
            cyclePeriodTicks = 100L,
        )
        gateway.applyDamage(world, pos, amount = 50, tool = ToolType.IRON_PICKAXE) // dur=50
        val handle = sut.start()

        // One cycle at tick 100 → +1 = 51.
        scheduler.advanceTicks(100L)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(51)

        handle.cancel()
        scheduler.advanceTicks(10_000L)

        // No further regen after cancellation.
        assertThat(gateway.getDurability(world, pos)).isEqualTo(51)
    }

    @Test
    fun `empty world repository — scheduler ticks without error`() {
        val scheduler = FakeScheduler()
        val gateway = InMemoryBlockStateGateway(initialTick = scheduler::currentTick)
        val recovery = BlockStateAutoRecoveryUseCase(gateway = gateway)
        val sut = BlockRecoveryScheduler(
            matchWorlds = EmptyWorldRepository,
            recovery = recovery,
            scheduler = scheduler,
            cyclePeriodTicks = 100L,
        )

        val handle = sut.start()
        scheduler.advanceTicks(1000L) // must not throw

        handle.cancel()
    }

    // -------------------------------------------------------------------------
    // Tiny in-test fakes — InMemoryMatchWorldRepository is owned by worker ζ
    // (Wave 5d), so this worker (α) inlines its own minimal IMatchWorldRepository
    // implementations to stay independent of ζ's pacing.
    // -------------------------------------------------------------------------

    private class SingleWorldRepository(private val mw: MatchWorld) : IMatchWorldRepository {
        override fun findByMatch(matchId: MatchId): MatchWorld? =
            if (mw.matchId == matchId) mw else null
        override fun save(matchWorld: MatchWorld) = Unit
        override fun remove(matchId: MatchId) = Unit
        override fun all(): List<MatchWorld> = listOf(mw)
    }

    private object EmptyWorldRepository : IMatchWorldRepository {
        override fun findByMatch(matchId: MatchId): MatchWorld? = null
        override fun save(matchWorld: MatchWorld) = Unit
        override fun remove(matchId: MatchId) = Unit
        override fun all(): List<MatchWorld> = emptyList()
    }
}
