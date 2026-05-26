package com.astarworks.astera.core.domain.perviewer

import com.astarworks.astera.core.domain.event.GuiOverrideApplied
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class GuiOverrideAppliedInvariantTest {

    private val handle = GuiHandle(GuiId("shop-main"), PlayerId(UUID.randomUUID()))
    private val slot = GuiSlot.Border(ItemId("gui-pane"))
    private val title = MessageKey("astera.gui.shop.title")

    @Test
    fun `requires exactly one of slotOverride or titleOverride`() {
        // Neither
        assertThatThrownBy {
            GuiOverrideApplied(handle)
        }.isInstanceOf(IllegalArgumentException::class.java)

        // Both
        assertThatThrownBy {
            GuiOverrideApplied(handle, SlotIndex(0), slot, title)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `slotOverride requires slotIndex`() {
        assertThatThrownBy {
            GuiOverrideApplied(handle, slotIndex = null, slotOverride = slot)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `titleOverride must not carry slotIndex`() {
        assertThatThrownBy {
            GuiOverrideApplied(handle, slotIndex = SlotIndex(0), titleOverride = title)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accepts a clean slot override`() {
        val ev = GuiOverrideApplied(handle, SlotIndex(3), slot)
        assertThat(ev.slotOverride).isEqualTo(slot)
        assertThat(ev.titleOverride).isNull()
    }

    @Test
    fun `accepts a clean title override`() {
        val ev = GuiOverrideApplied(handle, titleOverride = title)
        assertThat(ev.titleOverride).isEqualTo(title)
        assertThat(ev.slotIndex).isNull()
    }
}
