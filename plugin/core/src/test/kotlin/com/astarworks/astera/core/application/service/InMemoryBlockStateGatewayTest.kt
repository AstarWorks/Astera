package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryBlockStateGatewayTest {

    private val world = WorldId("arena-1")
    private val otherWorld = WorldId("arena-2")
    private val pos = Vec3(10.0, 64.0, 20.0)
    private val otherPos = Vec3(11.0, 64.0, 20.0)

    private var clock: Long = 0L
    private lateinit var gateway: InMemoryBlockStateGateway

    @BeforeEach
    fun setUp() {
        clock = 0L
        gateway = InMemoryBlockStateGateway(initialTick = { clock })
    }

    // -------------------------------------------------------------------------
    // getDurability
    // -------------------------------------------------------------------------

    @Test
    fun `getDurability returns MAX for untracked positions`() {
        assertThat(gateway.getDurability(world, pos)).isEqualTo(BlockState.MAX_DURABILITY)
    }

    @Test
    fun `getDurability reflects damage after applyDamage`() {
        gateway.applyDamage(world, pos, amount = 30, tool = ToolType.IRON)

        assertThat(gateway.getDurability(world, pos)).isEqualTo(70)
    }

    @Test
    fun `getDurability is per-world`() {
        gateway.applyDamage(world, pos, amount = 40, tool = ToolType.IRON)

        // same position in a different world is still at MAX
        assertThat(gateway.getDurability(otherWorld, pos)).isEqualTo(BlockState.MAX_DURABILITY)
    }

    // -------------------------------------------------------------------------
    // applyDamage
    // -------------------------------------------------------------------------

    @Test
    fun `applyDamage on untracked position seeds at MAX then deducts`() {
        val state = gateway.applyDamage(world, pos, amount = 25, tool = ToolType.STONE)

        assertThat(state.durability).isEqualTo(75)
        assertThat(state.lastDamagedTick).isEqualTo(0L)
        assertThat(state.damagedBy).isNull()
    }

    @Test
    fun `applyDamage clamps durability at 0 (no negative)`() {
        gateway.applyDamage(world, pos, amount = 80, tool = ToolType.DIAMOND)
        val state = gateway.applyDamage(world, pos, amount = 999, tool = ToolType.DIAMOND)

        assertThat(state.durability).isEqualTo(0)
        assertThat(state.isBroken).isTrue()
    }

    @Test
    fun `applyDamage with negative amount is clamped to 0 damage (no heal)`() {
        gateway.applyDamage(world, pos, amount = 30, tool = ToolType.IRON)
        val state = gateway.applyDamage(world, pos, amount = -10, tool = ToolType.IRON)

        // durability unchanged — negative damage must not heal
        assertThat(state.durability).isEqualTo(70)
    }

    @Test
    fun `applyDamage stamps initialTick into lastDamagedTick`() {
        clock = 1234L
        val state = gateway.applyDamage(world, pos, amount = 10, tool = ToolType.WOOD)

        assertThat(state.lastDamagedTick).isEqualTo(1234L)
    }

    @Test
    fun `applyDamage updates lastDamagedTick on subsequent hits`() {
        clock = 100L
        gateway.applyDamage(world, pos, amount = 10, tool = ToolType.WOOD)

        clock = 500L
        val state = gateway.applyDamage(world, pos, amount = 10, tool = ToolType.WOOD)

        assertThat(state.lastDamagedTick).isEqualTo(500L)
        assertThat(state.durability).isEqualTo(80)
    }

    // -------------------------------------------------------------------------
    // restore
    // -------------------------------------------------------------------------

    @Test
    fun `restore brings tracked block back to MAX_DURABILITY`() {
        gateway.applyDamage(world, pos, amount = 60, tool = ToolType.IRON)

        gateway.restore(world, pos)

        assertThat(gateway.getDurability(world, pos)).isEqualTo(BlockState.MAX_DURABILITY)
    }

    @Test
    fun `restore preserves the tracked blockType`() {
        val custom = BlockState(
            blockType = BlockType("astera:custom_brick"),
            durability = 10,
            lastDamagedTick = 50L,
            damagedBy = null,
        )
        gateway.track(world, pos, custom)

        gateway.restore(world, pos)

        val restored = gateway.tracked(world)[pos]
        assertThat(restored).isNotNull
        assertThat(restored!!.blockType).isEqualTo(BlockType("astera:custom_brick"))
        assertThat(restored.durability).isEqualTo(BlockState.MAX_DURABILITY)
    }

    @Test
    fun `restore on untracked position is a no-op`() {
        gateway.restore(world, pos) // must not throw

        // still untracked → still reads MAX
        assertThat(gateway.getDurability(world, pos)).isEqualTo(BlockState.MAX_DURABILITY)
        assertThat(gateway.tracked(world)).doesNotContainKey(pos)
    }

    // -------------------------------------------------------------------------
    // track
    // -------------------------------------------------------------------------

    @Test
    fun `track records the supplied state verbatim`() {
        val state = BlockState(
            blockType = BlockType("minecraft:cobblestone"),
            durability = 42,
            lastDamagedTick = 7L,
            damagedBy = null,
        )

        gateway.track(world, pos, state)

        assertThat(gateway.tracked(world)[pos]).isEqualTo(state)
        assertThat(gateway.getDurability(world, pos)).isEqualTo(42)
    }

    @Test
    fun `track overwrites a previous entry`() {
        val first = BlockState(
            blockType = BlockType("minecraft:stone"),
            durability = 50,
            lastDamagedTick = 1L,
            damagedBy = null,
        )
        gateway.track(world, pos, first)

        val second = first.copy(durability = 80, lastDamagedTick = 200L)
        gateway.track(world, pos, second)

        assertThat(gateway.tracked(world)[pos]).isEqualTo(second)
    }

    // -------------------------------------------------------------------------
    // tracked
    // -------------------------------------------------------------------------

    @Test
    fun `tracked returns an empty map for an unseen world`() {
        assertThat(gateway.tracked(world)).isEmpty()
    }

    @Test
    fun `tracked returns all positions for a world`() {
        gateway.applyDamage(world, pos, amount = 10, tool = ToolType.IRON)
        gateway.applyDamage(world, otherPos, amount = 20, tool = ToolType.IRON)

        val snapshot = gateway.tracked(world)

        assertThat(snapshot).hasSize(2)
        assertThat(snapshot[pos]?.durability).isEqualTo(90)
        assertThat(snapshot[otherPos]?.durability).isEqualTo(80)
    }

    @Test
    fun `tracked snapshot is independent of subsequent mutations`() {
        gateway.applyDamage(world, pos, amount = 10, tool = ToolType.IRON)
        val snapshot = gateway.tracked(world)

        gateway.applyDamage(world, pos, amount = 50, tool = ToolType.IRON)

        // snapshot reflects state at the moment of the tracked() call
        assertThat(snapshot[pos]?.durability).isEqualTo(90)
        // gateway has the new value
        assertThat(gateway.getDurability(world, pos)).isEqualTo(40)
    }

    @Test
    fun `tracked is scoped to one world`() {
        gateway.applyDamage(world, pos, amount = 10, tool = ToolType.IRON)
        gateway.applyDamage(otherWorld, otherPos, amount = 20, tool = ToolType.IRON)

        assertThat(gateway.tracked(world)).hasSize(1).containsKey(pos)
        assertThat(gateway.tracked(otherWorld)).hasSize(1).containsKey(otherPos)
    }
}
