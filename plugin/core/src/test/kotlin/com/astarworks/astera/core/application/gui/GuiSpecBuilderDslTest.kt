package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * DSL behaviour tests for [GuiSpecBuilder] (Wave 5i).
 *
 * Covers the four block-style slot constructors plus the layout helpers
 * ([border] / [borderFrame] / [repeatGrid]). The end-to-end "BedWars shop"
 * test exercises every primitive in combination so that any future change to
 * the DSL shape surfaces here first.
 */
class GuiSpecBuilderDslTest {

    private val shopId = GuiId("shop-main")
    private val titleKey = MessageKey("astera.gui.shop.title")

    // ----------------------------------------------------------------------
    // End-to-end — combined border + grid + multi-click + tooltip
    // ----------------------------------------------------------------------

    @Test
    fun `DSL builds BedWars-style shop with border + grid + multi-click`() {
        data class Offer(val id: String, val price: Int)
        val offers = listOf(
            Offer("iron-sword", 50),
            Offer("bow", 80),
        )

        val spec = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_27) {
            border(rows = 0..0, itemId = "gui-pane-dark")
            border(rows = 2..2, itemId = "gui-pane-dark")

            repeatGrid(items = offers, startIndex = 10, columns = 7) { offer, _ ->
                item(offer.id)
                onLeftClick("shop.purchase") {
                    "offer-id"(offer.id)
                    "qty"("1")
                }
                onRightClick("shop.preview") {
                    "offer-id"(offer.id)
                }
                tooltip(
                    "astera.shop.tooltip.left-buy",
                    "astera.shop.tooltip.right-preview",
                )
            }
        }

        // Border fills row 0 (slots 0..8) and row 2 (slots 18..26).
        for (i in 0..8) {
            assertThat(spec.slotAt(SlotIndex(i)))
                .isEqualTo(GuiSlot.Border(ItemId("gui-pane-dark")))
        }
        for (i in 18..26) {
            assertThat(spec.slotAt(SlotIndex(i)))
                .isEqualTo(GuiSlot.Border(ItemId("gui-pane-dark")))
        }

        // First offer lands at startIndex (10), second at 11. The interior
        // row is otherwise empty.
        val sword = spec.slotAt(SlotIndex(10))
        assertThat(sword).isInstanceOf(GuiSlot.Static::class.java)
        sword as GuiSlot.Static
        assertThat(sword.itemId).isEqualTo(ItemId("iron-sword"))
        assertThat(sword.onLeftClick).isEqualTo(
            GuiIntent("shop.purchase", mapOf("offer-id" to "iron-sword", "qty" to "1")),
        )
        assertThat(sword.onRightClick).isEqualTo(
            GuiIntent("shop.preview", mapOf("offer-id" to "iron-sword")),
        )
        assertThat(sword.onShiftClick).isNull()
        assertThat(sword.onMiddleClick).isNull()
        assertThat(sword.tooltip).containsExactly(
            MessageKey("astera.shop.tooltip.left-buy"),
            MessageKey("astera.shop.tooltip.right-preview"),
        )

        val bow = spec.slotAt(SlotIndex(11)) as GuiSlot.Static
        assertThat(bow.itemId).isEqualTo(ItemId("bow"))
        // Slot 12 (still in the interior row) is implicitly empty.
        assertThat(spec.slotAt(SlotIndex(12))).isEqualTo(GuiSlot.Empty)
    }

    // ----------------------------------------------------------------------
    // static {} block
    // ----------------------------------------------------------------------

    @Test
    fun `static block populates every click handler and tooltip`() {
        val spec = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_9) {
            static(4) {
                item("loadout-token")
                count(3)
                onLeftClick("loadout.equip") { "slot"("MAIN") }
                onRightClick("loadout.inspect")
                onShiftClick("loadout.equip-bulk") { "slot"("ALL") }
                onMiddleClick("loadout.copy-id")
                tooltip("astera.loadout.tooltip.equip")
                tooltip("astera.loadout.tooltip.inspect", "astera.loadout.tooltip.copy")
            }
        }

        val slot = spec.slotAt(SlotIndex(4)) as GuiSlot.Static
        assertThat(slot.itemId).isEqualTo(ItemId("loadout-token"))
        assertThat(slot.count).isEqualTo(3)
        assertThat(slot.onLeftClick)
            .isEqualTo(GuiIntent("loadout.equip", mapOf("slot" to "MAIN")))
        assertThat(slot.onRightClick).isEqualTo(GuiIntent("loadout.inspect"))
        assertThat(slot.onShiftClick)
            .isEqualTo(GuiIntent("loadout.equip-bulk", mapOf("slot" to "ALL")))
        assertThat(slot.onMiddleClick).isEqualTo(GuiIntent("loadout.copy-id"))
        assertThat(slot.tooltip).containsExactly(
            MessageKey("astera.loadout.tooltip.equip"),
            MessageKey("astera.loadout.tooltip.inspect"),
            MessageKey("astera.loadout.tooltip.copy"),
        )
    }

    // ----------------------------------------------------------------------
    // reactive {} block
    // ----------------------------------------------------------------------

    @Test
    fun `reactive block records binding and optional tooltip`() {
        val spec = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_9) {
            reactive(0, binding = "player.coins") {
                tooltip("astera.shop.tooltip.balance")
            }
            // Reactive with no body — still emits a slot.
            reactive(8, binding = "player.kills")
        }

        val coins = spec.slotAt(SlotIndex(0)) as GuiSlot.Reactive
        assertThat(coins.binding).isEqualTo(BindingKey("player.coins"))
        assertThat(coins.tooltip).containsExactly(MessageKey("astera.shop.tooltip.balance"))

        val kills = spec.slotAt(SlotIndex(8)) as GuiSlot.Reactive
        assertThat(kills.binding).isEqualTo(BindingKey("player.kills"))
        assertThat(kills.tooltip).isEmpty()
    }

    // ----------------------------------------------------------------------
    // confirmation {} block
    // ----------------------------------------------------------------------

    @Test
    fun `confirmation block builds Confirmation slot with both intents`() {
        val spec = GuiSpec.build(shopId, titleKey, GuiSize.HOPPER_5) {
            confirmation(2) {
                promptKey("astera.shop.confirm-purchase")
                confirmItem("wool-green")
                cancelItem("wool-red")
                confirmIntent("shop.purchase-confirm") {
                    "offer-id"("iron-sword")
                    "qty"("1")
                }
                cancelIntent("gui.close")
                tooltip("astera.shop.tooltip.confirm")
            }
        }

        val prompt = spec.slotAt(SlotIndex(2)) as GuiSlot.Confirmation
        assertThat(prompt.promptKey).isEqualTo(MessageKey("astera.shop.confirm-purchase"))
        assertThat(prompt.confirmItemId).isEqualTo(ItemId("wool-green"))
        assertThat(prompt.cancelItemId).isEqualTo(ItemId("wool-red"))
        assertThat(prompt.confirmIntent).isEqualTo(
            GuiIntent(
                "shop.purchase-confirm",
                mapOf("offer-id" to "iron-sword", "qty" to "1"),
            ),
        )
        assertThat(prompt.cancelIntent).isEqualTo(GuiIntent("gui.close"))
        assertThat(prompt.tooltip).containsExactly(MessageKey("astera.shop.tooltip.confirm"))
    }

    // ----------------------------------------------------------------------
    // textInput {} block
    // ----------------------------------------------------------------------

    @Test
    fun `textInput block builds TextInput slot with overrides`() {
        val spec = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_9) {
            textInput(0) {
                placeholderKey("astera.loadout.rename.placeholder")
                maxLength(24)
                onSubmit("loadout.rename") { "slot"("MAIN") }
            }
            // Defaults check — only required fields populated.
            textInput(8) {
                placeholderKey("astera.shop.search.placeholder")
                onSubmit("shop.search")
            }
        }

        val rename = spec.slotAt(SlotIndex(0)) as GuiSlot.TextInput
        assertThat(rename.placeholderKey)
            .isEqualTo(MessageKey("astera.loadout.rename.placeholder"))
        assertThat(rename.maxLength).isEqualTo(24)
        assertThat(rename.onSubmitIntent)
            .isEqualTo(GuiIntent("loadout.rename", mapOf("slot" to "MAIN")))

        val search = spec.slotAt(SlotIndex(8)) as GuiSlot.TextInput
        assertThat(search.maxLength).isEqualTo(32) // domain default
        assertThat(search.onSubmitIntent).isEqualTo(GuiIntent("shop.search"))
    }

    // ----------------------------------------------------------------------
    // borderFrame
    // ----------------------------------------------------------------------

    @Test
    fun `borderFrame fills perimeter of CHEST_27 leaving interior empty`() {
        val spec = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_27) {
            borderFrame(itemId = "gui-pane-dark")
        }

        val expected = GuiSlot.Border(ItemId("gui-pane-dark"))

        // Row 0 (top): all 9 cells are border.
        for (i in 0..8) {
            assertThat(spec.slotAt(SlotIndex(i)))
                .describedAs("top row slot $i")
                .isEqualTo(expected)
        }
        // Row 2 (bottom): all 9 cells are border.
        for (i in 18..26) {
            assertThat(spec.slotAt(SlotIndex(i)))
                .describedAs("bottom row slot $i")
                .isEqualTo(expected)
        }
        // Row 1 (middle): only col 0 (slot 9) and col 8 (slot 17) are border.
        assertThat(spec.slotAt(SlotIndex(9))).isEqualTo(expected)
        assertThat(spec.slotAt(SlotIndex(17))).isEqualTo(expected)
        for (i in 10..16) {
            assertThat(spec.slotAt(SlotIndex(i)))
                .describedAs("interior slot $i must be empty")
                .isEqualTo(GuiSlot.Empty)
        }
    }

    // ----------------------------------------------------------------------
    // borderColumn — vertical separator
    // ----------------------------------------------------------------------

    @Test
    fun `borderColumn places border across every row in named columns`() {
        val spec = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_27) {
            borderColumn(columns = 4..4, itemId = "gui-pane-light")
        }

        val expected = GuiSlot.Border(ItemId("gui-pane-light"))
        // Column 4 spans slots 4, 13, 22 across three chest rows.
        assertThat(spec.slotAt(SlotIndex(4))).isEqualTo(expected)
        assertThat(spec.slotAt(SlotIndex(13))).isEqualTo(expected)
        assertThat(spec.slotAt(SlotIndex(22))).isEqualTo(expected)
        // Adjacent columns untouched.
        assertThat(spec.slotAt(SlotIndex(3))).isEqualTo(GuiSlot.Empty)
        assertThat(spec.slotAt(SlotIndex(5))).isEqualTo(GuiSlot.Empty)
    }

    // ----------------------------------------------------------------------
    // empty() helper
    // ----------------------------------------------------------------------

    @Test
    fun `empty helper materialises a deliberate gap`() {
        val spec = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_9) {
            static(0) { item("filler") }
            empty(1)
        }
        // Slot 0 has Static, slot 1 has the explicit Empty, slot 2 is also
        // Empty (absent from the map) — slotAt collapses both to GuiSlot.Empty.
        assertThat(spec.slotAt(SlotIndex(0))).isInstanceOf(GuiSlot.Static::class.java)
        assertThat(spec.slotAt(SlotIndex(1))).isEqualTo(GuiSlot.Empty)
        assertThat(spec.slotAt(SlotIndex(2))).isEqualTo(GuiSlot.Empty)
        // The empty(1) call still registered an entry — we can observe that
        // by checking the slots map directly.
        assertThat(spec.slots).containsKey(SlotIndex(1))
        assertThat(spec.slots[SlotIndex(1)]).isEqualTo(GuiSlot.Empty)
    }

    // ----------------------------------------------------------------------
    // Validation: missing item, bad columns, out-of-bounds startIndex
    // ----------------------------------------------------------------------

    @Test
    fun `static without item call throws meaningful error`() {
        assertThatThrownBy {
            GuiSpec.build(shopId, titleKey, GuiSize.CHEST_9) {
                static(0) {
                    // No item(...) — should fail at build time.
                    onLeftClick("noop.action")
                }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("item(")
    }

    @Test
    fun `confirmation without all required fields throws meaningful error`() {
        assertThatThrownBy {
            GuiSpec.build(shopId, titleKey, GuiSize.HOPPER_5) {
                confirmation(0) {
                    promptKey("astera.shop.confirm-purchase")
                    confirmItem("wool-green")
                    // missing cancelItem + intents
                }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `repeatGrid rejects non-positive columns and oversize columns`() {
        assertThatThrownBy {
            GuiSpec.build(shopId, titleKey, GuiSize.CHEST_27) {
                repeatGrid(items = listOf("a"), startIndex = 0, columns = 0) { _, _ ->
                    item("filler")
                }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("columns")

        assertThatThrownBy {
            GuiSpec.build(shopId, titleKey, GuiSize.CHEST_27) {
                repeatGrid(items = listOf("a"), startIndex = 0, columns = 10) { _, _ ->
                    item("filler")
                }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("columns")
    }

    // ----------------------------------------------------------------------
    // Grid wrap math
    // ----------------------------------------------------------------------

    @Test
    fun `repeatGrid wraps onto the next chest row at the columns boundary`() {
        // 4 items, columns=2, startIndex=10 — placement:
        //   loop 0 -> 10 + 0*9 + 0 = 10
        //   loop 1 -> 10 + 0*9 + 1 = 11
        //   loop 2 -> 10 + 1*9 + 0 = 19
        //   loop 3 -> 10 + 1*9 + 1 = 20
        val itemIds = listOf("a", "b", "c", "d")
        val placed = mutableMapOf<Int, String>()
        val spec = GuiSpec.build(shopId, titleKey, GuiSize.CHEST_27) {
            repeatGrid(items = itemIds, startIndex = 10, columns = 2) { id, _ ->
                item(id)
            }
        }
        for ((idx, _) in spec.slots) {
            val s = spec.slotAt(idx) as? GuiSlot.Static ?: continue
            placed[idx.value] = s.itemId.value
        }
        assertThat(placed).containsExactlyInAnyOrderEntriesOf(
            mapOf(10 to "a", 11 to "b", 19 to "c", 20 to "d"),
        )
    }
}
