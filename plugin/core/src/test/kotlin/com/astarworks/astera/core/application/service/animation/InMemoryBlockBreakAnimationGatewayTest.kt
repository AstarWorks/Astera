package com.astarworks.astera.core.application.service.animation

import com.astarworks.astera.core.domain.animation.block.BlockBreakStage
import com.astarworks.astera.core.domain.event.BlockBreakStageChanged
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryBlockBreakAnimationGatewayTest {

    private val bus = FakeDomainEventBus()
    private val gateway = InMemoryBlockBreakAnimationGateway(bus, tickClock = { 300L })
    private val world = WorldId("world")
    private val pos = Vec3(10.0, 64.0, 10.0)

    @Test
    fun `setBreakStage publishes BlockBreakStageChanged`() {
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_3)
        val events = bus.publishedOfType<BlockBreakStageChanged>()
        assertThat(events).hasSize(1)
        assertThat(events.first().previousStage).isNull()
        assertThat(events.first().newStage).isEqualTo(BlockBreakStage.STAGE_3)
        assertThat(gateway.getCurrentStage(world, pos)).isEqualTo(BlockBreakStage.STAGE_3)
    }

    @Test
    fun `setBreakStage dedupes identical calls`() {
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_2)
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_2)
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_2)
        assertThat(bus.publishedOfType<BlockBreakStageChanged>()).hasSize(1)
    }

    @Test
    fun `setBreakStage stage progression publishes each transition`() {
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_0)
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_5)
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_9)
        val events = bus.publishedOfType<BlockBreakStageChanged>()
        assertThat(events).hasSize(3)
        assertThat(events.map { it.newStage }).containsExactly(
            BlockBreakStage.STAGE_0,
            BlockBreakStage.STAGE_5,
            BlockBreakStage.STAGE_9,
        )
        assertThat(events[1].previousStage).isEqualTo(BlockBreakStage.STAGE_0)
    }

    @Test
    fun `setBreakStage RESET clears tracker`() {
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_5)
        gateway.setBreakStage(world, pos, BlockBreakStage.RESET)
        assertThat(gateway.getCurrentStage(world, pos)).isNull()
    }

    @Test
    fun `cancelBreakAnimation publishes RESET when tracked`() {
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_4)
        bus.published.clear()
        gateway.cancelBreakAnimation(world, pos)
        val events = bus.publishedOfType<BlockBreakStageChanged>()
        assertThat(events).hasSize(1)
        assertThat(events.first().previousStage).isEqualTo(BlockBreakStage.STAGE_4)
        assertThat(events.first().newStage).isEqualTo(BlockBreakStage.RESET)
        assertThat(gateway.getCurrentStage(world, pos)).isNull()
    }

    @Test
    fun `cancelBreakAnimation is idempotent on untracked position`() {
        gateway.cancelBreakAnimation(world, pos)
        assertThat(bus.publishedOfType<BlockBreakStageChanged>()).isEmpty()
    }

    @Test
    fun `setBreakStage preserves viewer subset in event`() {
        val viewers = setOf(PlayerId(UUID.randomUUID()), PlayerId(UUID.randomUUID()))
        gateway.setBreakStage(world, pos, BlockBreakStage.STAGE_1, broadcastViewers = viewers)
        val event = bus.publishedOfType<BlockBreakStageChanged>().single()
        assertThat(event.viewers).isEqualTo(viewers)
    }
}
