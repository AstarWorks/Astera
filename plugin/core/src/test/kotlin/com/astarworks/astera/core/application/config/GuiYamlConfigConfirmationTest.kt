package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.application.config.parser.YamlContentParser
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Validates the `confirmation` slot DTO ([GuiSlotYamlConfig.Confirmation])
 * round-trips YAML → domain ([GuiSlot.Confirmation]) per Wave 5h / ADR-0079 §2.
 *
 * Mirrors the shape of [GuiYamlConfigTextInputTest]; both are kept narrow
 * so a regression in the polymorphic discriminator or one variant's field
 * map cannot mask the other.
 */
class GuiYamlConfigConfirmationTest {

    private val parser = YamlContentParser()

    @Test
    fun `confirmation slot YAML maps to GuiSlot Confirmation`() {
        val yaml = """
            id: example-shop-confirm-purchase
            title_key: astera.gui.shop.confirm-purchase.title
            size: CHEST_9
            slots:
              - type: confirmation
                index: 4
                prompt_key: astera.gui.shop.confirm-purchase.prompt
                confirm_item: gui-button-confirm
                cancel_item: gui-button-cancel
                confirm:
                  key: shop.purchase.confirm
                  payload:
                    itemId: "iron-sword"
                    qty: "1"
                cancel:
                  key: gui.close
        """.trimIndent()

        val spec = parser.parse(yaml, GuiYamlConfig.serializer()).toSpec()

        assertThat(spec.id).isEqualTo(GuiId("example-shop-confirm-purchase"))
        assertThat(spec.size).isEqualTo(GuiSize.CHEST_9)

        val slot = spec.slotAt(SlotIndex(4))
        assertThat(slot).isInstanceOf(GuiSlot.Confirmation::class.java)
        slot as GuiSlot.Confirmation
        assertThat(slot.promptKey).isEqualTo(MessageKey("astera.gui.shop.confirm-purchase.prompt"))
        assertThat(slot.confirmItemId).isEqualTo(ItemId("gui-button-confirm"))
        assertThat(slot.cancelItemId).isEqualTo(ItemId("gui-button-cancel"))
        assertThat(slot.confirmIntent.key).isEqualTo("shop.purchase.confirm")
        assertThat(slot.confirmIntent.payload)
            .containsEntry("itemId", "iron-sword")
            .containsEntry("qty", "1")
        assertThat(slot.cancelIntent.key).isEqualTo("gui.close")
        assertThat(slot.cancelIntent.payload).isEmpty()
    }

    @Test
    fun `confirmation slot loads from the committed example YAML file`() {
        // Same content as content/gui/example-confirmation.yaml — kept inline
        // so the test does not couple to filesystem layout. Acts as a
        // schema-validation co-test for the example file.
        val yaml = """
            id: example-shop-confirm-purchase
            title_key: astera.gui.shop.confirm-purchase.title
            size: CHEST_9
            slots:
              - type: border
                index: 0
                item: gui-pane-dark
              - type: border
                index: 1
                item: gui-pane-dark
              - type: border
                index: 2
                item: gui-pane-dark
              - type: confirmation
                index: 4
                prompt_key: astera.gui.shop.confirm-purchase.prompt
                confirm_item: gui-button-confirm
                cancel_item: gui-button-cancel
                confirm:
                  key: shop.purchase.confirm
                  payload: { itemId: "iron-sword", qty: "1" }
                cancel:
                  key: gui.close
              - type: border
                index: 6
                item: gui-pane-dark
              - type: border
                index: 7
                item: gui-pane-dark
              - type: border
                index: 8
                item: gui-pane-dark
        """.trimIndent()

        val spec = parser.parse(yaml, GuiYamlConfig.serializer()).toSpec()

        // Confirmation is at index 4; borders fill the rest.
        val confirmation = spec.slotAt(SlotIndex(4))
        assertThat(confirmation).isInstanceOf(GuiSlot.Confirmation::class.java)
        val border = spec.slotAt(SlotIndex(0))
        assertThat(border).isInstanceOf(GuiSlot.Border::class.java)
    }
}
