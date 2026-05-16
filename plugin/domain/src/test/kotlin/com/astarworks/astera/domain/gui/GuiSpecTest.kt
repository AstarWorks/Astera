package com.astarworks.astera.domain.gui

import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class GuiSpecTest {

    private val shopId = GuiId("shop-main")
    private val titleKey = MessageKey("astera.gui.shop.title")

    @Test
    fun `build assembles spec with declared slots and treats missing slots as Empty`() {
        val sword = ItemId("iron-sword")
        val border = ItemId("gui-pane-dark")
        val purchase = GuiIntent("shop.purchase", mapOf("itemId" to "iron-sword"))

        val spec = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_27) {
            slot(SlotIndex(0), GuiSlot.Border(border))
            slot(SlotIndex(13), GuiSlot.Static(sword, onClick = purchase))
        }

        assertThat(spec.id).isEqualTo(shopId)
        assertThat(spec.title).isEqualTo(titleKey)
        assertThat(spec.size).isEqualTo(GuiSize.CHEST_27)
        assertThat(spec.slotAt(SlotIndex(0))).isEqualTo(GuiSlot.Border(border))
        assertThat(spec.slotAt(SlotIndex(13))).isEqualTo(GuiSlot.Static(sword, onClick = purchase))
        assertThat(spec.slotAt(SlotIndex(7))).isEqualTo(GuiSlot.Empty)
        assertThat(spec.slotAt(SlotIndex(26))).isEqualTo(GuiSlot.Empty)
    }

    @Test
    fun `withSlot returns updated spec without mutating original (PersistentMap sharing)`() {
        val original = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_9) {
            slot(SlotIndex(0), GuiSlot.Border(ItemId("gui-pane-dark")))
        }
        val updated = original.withSlot(
            SlotIndex(4),
            GuiSlot.Static(ItemId("health-potion")),
        )

        assertThat(original.slotAt(SlotIndex(4))).isEqualTo(GuiSlot.Empty)
        assertThat(updated.slotAt(SlotIndex(4))).isEqualTo(GuiSlot.Static(ItemId("health-potion")))
        // Original's existing slot is preserved in the updated copy.
        assertThat(updated.slotAt(SlotIndex(0))).isEqualTo(GuiSlot.Border(ItemId("gui-pane-dark")))
    }

    @Test
    fun `slot index beyond GuiSize is rejected at build time`() {
        assertThatThrownBy {
            GuiSpec.build(shopId, titleKey, GuiSize.CHEST_27) {
                // CHEST_27 max index is 26 — 27 must fail.
                slot(SlotIndex(27), GuiSlot.Empty)
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("out of bounds")
            .hasMessageContaining("CHEST_27")
    }

    @ParameterizedTest
    @EnumSource(GuiSize::class)
    fun `GuiSize contains exactly indices in 0 until slotCount`(size: GuiSize) {
        assertThat(size.contains(SlotIndex(0))).isTrue()
        assertThat(size.contains(SlotIndex(size.slotCount - 1))).isTrue()
        assertThat(size.contains(SlotIndex(size.slotCount))).isFalse()
        assertThat(size.contains(SlotIndex(size.slotCount + 10))).isFalse()
    }

    @Test
    fun `GuiIntent is plain data and serialisable in shape (no lambdas, equals by value)`() {
        val a = GuiIntent("shop.purchase", mapOf("itemId" to "iron-sword", "qty" to "1"))
        val b = GuiIntent("shop.purchase", mapOf("itemId" to "iron-sword", "qty" to "1"))
        val c = GuiIntent("shop.purchase", mapOf("itemId" to "iron-sword", "qty" to "2"))

        // Data equality (no behavioural / captured state hiding inside) — the
        // intent is a value, suitable for logging, replay, and (future)
        // serialisation by an application-side DTO.
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)

        // Empty payload is the default, keeping construction terse.
        assertThat(GuiIntent("gui.close").payload).isEmpty()
    }

    @Test
    fun `validation rules surface bad input immediately`() {
        // Blank intent key.
        assertThatThrownBy { GuiIntent("") }
            .isInstanceOf(IllegalArgumentException::class.java)

        // Invalid intent key shape (uppercase).
        assertThatThrownBy { GuiIntent("Shop.Purchase") }
            .isInstanceOf(IllegalArgumentException::class.java)

        // SlotIndex negative.
        assertThatThrownBy { SlotIndex(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)

        // GuiId casing.
        assertThatThrownBy { GuiId("Shop-Main") }
            .isInstanceOf(IllegalArgumentException::class.java)

        // GuiSlot.Static count clamp.
        assertThatThrownBy { GuiSlot.Static(ItemId("iron-sword"), count = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { GuiSlot.Static(ItemId("iron-sword"), count = 65) }
            .isInstanceOf(IllegalArgumentException::class.java)

        // BindingKey casing.
        assertThatThrownBy { BindingKey("Player.Coins") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
