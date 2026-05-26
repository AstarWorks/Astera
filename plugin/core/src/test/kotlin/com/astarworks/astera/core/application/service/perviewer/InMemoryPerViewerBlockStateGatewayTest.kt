package com.astarworks.astera.core.application.service.perviewer

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.BlockOverrideApplied
import com.astarworks.astera.core.domain.event.PerViewerOverridesCleared
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.perviewer.ClearReason
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPerViewerBlockStateGatewayTest {

    private val viewer = PlayerId(UUID.randomUUID())
    private val world = WorldId("astera_pvp_arena")
    private val pos = Vec3(10.0, 64.0, 20.0)

    @Test
    fun `overrideBlockType rejects unknown viewer`() {
        val gw = InMemoryPerViewerBlockStateGateway()
        val result = gw.overrideBlockType(viewer, world, pos, BlockType("minecraft:stone"))
        assertThat(result).isEqualTo(Result.failure(PerViewerOverrideError.UnknownViewer))
    }

    @Test
    fun `overrideBlockType publishes BlockOverrideApplied with overrideType`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerBlockStateGateway(bus)
        gw.trackViewer(viewer)
        val type = BlockType("astera:hidden_door")

        gw.overrideBlockType(viewer, world, pos, type)

        val events = bus.publishedOfType<BlockOverrideApplied>()
        assertThat(events).hasSize(1)
        assertThat(events[0].overrideType).isEqualTo(type)
        assertThat(events[0].hidden).isFalse
    }

    @Test
    fun `overrideTexture replaces overrideBlockType because invariant is exclusive`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerBlockStateGateway(bus)
        gw.trackViewer(viewer)

        gw.overrideBlockType(viewer, world, pos, BlockType("minecraft:stone"))
        gw.overrideTexture(viewer, world, pos, HudGlyphRef("highlight/arrow"))

        val events = bus.publishedOfType<BlockOverrideApplied>()
        assertThat(events).hasSize(2)
        assertThat(events[0].overrideType).isNotNull
        assertThat(events[1].overrideTexture).isEqualTo(HudGlyphRef("highlight/arrow"))
        assertThat(events[1].overrideType).isNull()
    }

    @Test
    fun `hideBlock is idempotent and publishes once`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerBlockStateGateway(bus)
        gw.trackViewer(viewer)

        gw.hideBlock(viewer, world, pos)
        gw.hideBlock(viewer, world, pos)

        val events = bus.publishedOfType<BlockOverrideApplied>()
        assertThat(events).hasSize(1)
        assertThat(events[0].hidden).isTrue
    }

    @Test
    fun `showBlock restores the prior type override`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerBlockStateGateway(bus)
        gw.trackViewer(viewer)
        val type = BlockType("astera:bomb_disguise")

        gw.overrideBlockType(viewer, world, pos, type)
        gw.hideBlock(viewer, world, pos)
        gw.showBlock(viewer, world, pos)

        val events = bus.publishedOfType<BlockOverrideApplied>()
        // type-override -> hidden -> restored-type-override
        assertThat(events).hasSize(3)
        assertThat(events[0].overrideType).isEqualTo(type)
        assertThat(events[1].hidden).isTrue
        assertThat(events[2].overrideType).isEqualTo(type)
    }

    @Test
    fun `showBlock is no-op when not hidden`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerBlockStateGateway(bus)
        gw.trackViewer(viewer)

        gw.showBlock(viewer, world, pos)

        assertThat(bus.publishedOfType<BlockOverrideApplied>()).isEmpty()
    }

    @Test
    fun `clearOverride drops every axis and emits one cleared event`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerBlockStateGateway(bus)
        gw.trackViewer(viewer)
        gw.overrideTexture(viewer, world, pos, HudGlyphRef("highlight/star"))

        gw.clearOverride(viewer, world, pos)

        val cleared = bus.publishedOfType<PerViewerOverridesCleared>()
        assertThat(cleared).hasSize(1)
        assertThat(cleared[0].gatewayFamily).isEqualTo(PerViewerOverridesCleared.FAMILY_BLOCK)
        assertThat(cleared[0].reason).isEqualTo(ClearReason.Explicit)
    }

    @Test
    fun `untrackViewer sweeps every position and emits PlayerQuit cleared`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerBlockStateGateway(bus)
        gw.trackViewer(viewer)
        gw.hideBlock(viewer, world, pos)
        gw.hideBlock(viewer, world, Vec3(11.0, 64.0, 20.0))

        gw.untrackViewer(viewer)

        val cleared = bus.publishedOfType<PerViewerOverridesCleared>()
            .filter { it.reason == ClearReason.PlayerQuit }
        assertThat(cleared).hasSize(1)
        assertThat(cleared[0].clearedCount).isEqualTo(2)
    }
}
