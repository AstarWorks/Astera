package com.astarworks.astera.match.application.usecase.block

import com.astarworks.astera.core.application.service.InMemoryBlockStateGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BlockStateAutoRecoveryUseCaseTest {

    private val world = WorldId("arena-1")
    private val pos = Vec3(0.0, 64.0, 0.0)
    private val pos2 = Vec3(1.0, 64.0, 0.0)
    private val pos3 = Vec3(2.0, 64.0, 0.0)

    private var clock: Long = 0L
    private lateinit var gateway: InMemoryBlockStateGateway
    private lateinit var sut: BlockStateAutoRecoveryUseCase

    @BeforeEach
    fun setUp() {
        clock = 0L
        gateway = InMemoryBlockStateGateway(initialTick = { clock })
        sut = BlockStateAutoRecoveryUseCase(
            gateway = gateway,
            coolOffTicks = 600L,
            regenPerCycle = 5,
        )
    }

    // -------------------------------------------------------------------------
    // Construction guards
    // -------------------------------------------------------------------------

    @Test
    fun `rejects negative coolOffTicks`() {
        assertThatIllegalArgumentException().isThrownBy {
            BlockStateAutoRecoveryUseCase(gateway, coolOffTicks = -1L)
        }
    }

    @Test
    fun `rejects non-positive regenPerCycle`() {
        assertThatIllegalArgumentException().isThrownBy {
            BlockStateAutoRecoveryUseCase(gateway, regenPerCycle = 0)
        }
        assertThatIllegalArgumentException().isThrownBy {
            BlockStateAutoRecoveryUseCase(gateway, regenPerCycle = -5)
        }
    }

    // -------------------------------------------------------------------------
    // Cool-off gate
    // -------------------------------------------------------------------------

    @Test
    fun `block damaged at tick 0 is not regenerated at tick 599`() {
        clock = 0L
        gateway.applyDamage(world, pos, amount = 20, tool = ToolType.IRON_PICKAXE) // dur=80

        val regen = sut.tick(world, currentTick = 599L)

        assertThat(regen).isEqualTo(0)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(80)
    }

    @Test
    fun `block damaged at tick 0 regenerates by 5 at tick 600`() {
        clock = 0L
        gateway.applyDamage(world, pos, amount = 20, tool = ToolType.IRON_PICKAXE) // dur=80

        val regen = sut.tick(world, currentTick = 600L)

        assertThat(regen).isEqualTo(1)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(85)
    }

    @Test
    fun `block damaged later uses its own lastDamagedTick for cool-off`() {
        clock = 100L
        gateway.applyDamage(world, pos, amount = 30, tool = ToolType.IRON_PICKAXE) // dur=70 at tick 100

        // tick 699 = 599 ticks since damage → still inside cool-off
        assertThat(sut.tick(world, currentTick = 699L)).isEqualTo(0)
        // tick 700 = exactly 600 ticks since damage → eligible
        assertThat(sut.tick(world, currentTick = 700L)).isEqualTo(1)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(75)
    }

    // -------------------------------------------------------------------------
    // Edge cases on durability
    // -------------------------------------------------------------------------

    @Test
    fun `broken blocks (durability == 0) are NOT regenerated`() {
        clock = 0L
        gateway.applyDamage(world, pos, amount = 100, tool = ToolType.DIAMOND_PICKAXE) // dur=0

        val regen = sut.tick(world, currentTick = 10_000L)

        assertThat(regen).isEqualTo(0)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(0)
    }

    @Test
    fun `already-MAX blocks are not regenerated`() {
        // Track at MAX manually (e.g. a placed block that's never been damaged)
        gateway.track(
            world, pos,
            BlockState(
                blockType = BlockType("minecraft:cobblestone"),
                durability = BlockState.MAX_DURABILITY,
                lastDamagedTick = 0L,
                damagedBy = null,
            ),
        )

        val regen = sut.tick(world, currentTick = 10_000L)

        assertThat(regen).isEqualTo(0)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(BlockState.MAX_DURABILITY)
    }

    @Test
    fun `regen clamps at MAX_DURABILITY`() {
        // Track with durability close to MAX so one cycle would overshoot.
        gateway.track(
            world, pos,
            BlockState(
                blockType = BlockType("minecraft:cobblestone"),
                durability = 98,
                lastDamagedTick = 0L,
                damagedBy = null,
            ),
        )

        val regen = sut.tick(world, currentTick = 1000L)

        assertThat(regen).isEqualTo(1)
        // 98 + 5 = 103 → clamped to MAX_DURABILITY (100)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(BlockState.MAX_DURABILITY)
    }

    // -------------------------------------------------------------------------
    // Mixed populations
    // -------------------------------------------------------------------------

    @Test
    fun `mixed population — only eligible blocks regenerate, count is correct`() {
        clock = 0L
        // pos: eligible (damaged, past cool-off, > 0, < MAX)
        gateway.applyDamage(world, pos, amount = 40, tool = ToolType.IRON_PICKAXE) // dur=60
        // pos2: broken — skipped
        gateway.applyDamage(world, pos2, amount = 100, tool = ToolType.IRON_PICKAXE) // dur=0
        // pos3: damaged but inside cool-off — skipped at current tick
        clock = 500L
        gateway.applyDamage(world, pos3, amount = 20, tool = ToolType.IRON_PICKAXE) // dur=80, lastDamagedTick=500

        val regen = sut.tick(world, currentTick = 600L)

        // Only pos qualifies (lastDamagedTick=0, 0+600 <= 600).
        // pos2 broken. pos3 lastDamagedTick=500, 500+600=1100 > 600 → skipped.
        assertThat(regen).isEqualTo(1)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(65)
        assertThat(gateway.getDurability(world, pos2)).isEqualTo(0)
        assertThat(gateway.getDurability(world, pos3)).isEqualTo(80)
    }

    @Test
    fun `multiple cycles progressively heal a damaged block back toward MAX`() {
        clock = 0L
        gateway.applyDamage(world, pos, amount = 50, tool = ToolType.IRON_PICKAXE) // dur=50

        // First eligible cycle
        sut.tick(world, currentTick = 600L)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(55)

        // Each subsequent cycle adds 5 because lastDamagedTick is unchanged
        // by the use case (only applyDamage bumps it). 9 more cycles → +45 = 100.
        repeat(9) { sut.tick(world, currentTick = 600L) }
        assertThat(gateway.getDurability(world, pos)).isEqualTo(BlockState.MAX_DURABILITY)
    }

    @Test
    fun `tick on empty world is a no-op returning 0`() {
        val regen = sut.tick(world, currentTick = 10_000L)

        assertThat(regen).isEqualTo(0)
        assertThat(gateway.tracked(world)).isEmpty()
    }

    @Test
    fun `tick on a different world does not regenerate this world`() {
        clock = 0L
        gateway.applyDamage(world, pos, amount = 30, tool = ToolType.IRON_PICKAXE) // dur=70

        val otherWorld = WorldId("arena-2")
        val regen = sut.tick(otherWorld, currentTick = 10_000L)

        assertThat(regen).isEqualTo(0)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(70)
    }
}
