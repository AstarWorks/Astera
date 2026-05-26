package com.astarworks.astera.core.application.service.perviewer

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.GuiOverrideApplied
import com.astarworks.astera.core.domain.event.PerViewerOverridesCleared
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.perviewer.ClearReason
import com.astarworks.astera.core.domain.perviewer.GuiHandle
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPerViewerGuiStateGatewayTest {

    private val viewer = PlayerId(UUID.randomUUID())
    private val other = PlayerId(UUID.randomUUID())
    private val handle = GuiHandle(GuiId("shop-main"), viewer)

    @Test
    fun `overrideSlotContent rejects mismatched handle viewer`() {
        val gw = InMemoryPerViewerGuiStateGateway()
        val crossHandle = GuiHandle(GuiId("shop-main"), other)

        val result = gw.overrideSlotContent(
            viewer = viewer,
            handle = crossHandle,
            slotIndex = SlotIndex(0),
            content = GuiSlot.Border(ItemId("gui-pane")),
        )

        assertThat(result).isEqualTo(Result.failure(PerViewerOverrideError.UnknownTarget))
    }

    @Test
    fun `overrideSlotContent publishes GuiOverrideApplied on first set then is idempotent`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerGuiStateGateway(bus)
        val slot = GuiSlot.Border(ItemId("gui-pane"))

        gw.overrideSlotContent(viewer, handle, SlotIndex(0), slot)
        gw.overrideSlotContent(viewer, handle, SlotIndex(0), slot)

        val events = bus.publishedOfType<GuiOverrideApplied>()
        assertThat(events).hasSize(1)
        assertThat(events[0].slotOverride).isEqualTo(slot)
        assertThat(events[0].slotIndex).isEqualTo(SlotIndex(0))
    }

    @Test
    fun `overrideTitle publishes GuiOverrideApplied with titleOverride`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerGuiStateGateway(bus)
        val title = MessageKey("astera.gui.shop.viewer-suffix")

        gw.overrideTitle(viewer, handle, title)

        val events = bus.publishedOfType<GuiOverrideApplied>()
        assertThat(events).hasSize(1)
        assertThat(events[0].titleOverride).isEqualTo(title)
        assertThat(events[0].slotIndex).isNull()
    }

    @Test
    fun `clearOverridesForViewer counts slot + title overrides`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerGuiStateGateway(bus)
        gw.overrideSlotContent(viewer, handle, SlotIndex(0), GuiSlot.Border(ItemId("gui-pane")))
        gw.overrideSlotContent(viewer, handle, SlotIndex(1), GuiSlot.Border(ItemId("gui-pane")))
        gw.overrideTitle(viewer, handle, MessageKey("astera.gui.shop.private"))

        val count = gw.clearOverridesForViewer(viewer, handle)

        assertThat(count).isEqualTo(3)
        val cleared = bus.publishedOfType<PerViewerOverridesCleared>()
        assertThat(cleared).hasSize(1)
        assertThat(cleared[0].clearedCount).isEqualTo(3)
        assertThat(cleared[0].reason).isEqualTo(ClearReason.Explicit)
    }

    @Test
    fun `clearAllOverridesForViewer returns handle count, not slot count`() {
        val gw = InMemoryPerViewerGuiStateGateway()
        val handleA = GuiHandle(GuiId("shop-main"), viewer)
        val handleB = GuiHandle(GuiId("loadout-editor"), viewer)
        gw.overrideSlotContent(viewer, handleA, SlotIndex(0), GuiSlot.Border(ItemId("gui-pane")))
        gw.overrideSlotContent(viewer, handleA, SlotIndex(1), GuiSlot.Border(ItemId("gui-pane")))
        gw.overrideTitle(viewer, handleB, MessageKey("astera.gui.loadout.private"))

        val count = gw.clearAllOverridesForViewer(viewer)

        // Two handles touched, not three slot entries.
        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `sweepOnQuit emits PlayerQuit cleared reason`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerGuiStateGateway(bus)
        gw.overrideSlotContent(viewer, handle, SlotIndex(0), GuiSlot.Border(ItemId("gui-pane")))

        gw.sweepOnQuit(viewer)

        val cleared = bus.publishedOfType<PerViewerOverridesCleared>()
        assertThat(cleared).hasSize(1)
        assertThat(cleared[0].reason).isEqualTo(ClearReason.PlayerQuit)
        assertThat(cleared[0].gatewayFamily).isEqualTo(PerViewerOverridesCleared.FAMILY_GUI)
    }

    @Test
    fun `idempotent overrideTitle suppresses duplicate event`() {
        val bus = FakeDomainEventBus()
        val gw = InMemoryPerViewerGuiStateGateway(bus)
        val title = MessageKey("astera.gui.shop.private")

        gw.overrideTitle(viewer, handle, title)
        gw.overrideTitle(viewer, handle, title)

        assertThat(bus.publishedOfType<GuiOverrideApplied>()).hasSize(1)
    }
}
