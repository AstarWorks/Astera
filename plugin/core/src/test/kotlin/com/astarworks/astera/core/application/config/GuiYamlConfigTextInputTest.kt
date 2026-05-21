package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.application.config.parser.YamlContentParser
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

/**
 * Validates the `text_input` slot DTO ([GuiSlotYamlConfig.TextInput])
 * round-trips YAML → domain ([GuiSlot.TextInput]) per Wave 5h / ADR-0079 §2.
 */
class GuiYamlConfigTextInputTest {

    private val parser = YamlContentParser()

    @Test
    fun `text_input slot YAML maps to GuiSlot TextInput with explicit max length`() {
        val yaml = """
            id: example-loadout-rename
            title_key: astera.gui.loadout.rename.title
            size: CHEST_9
            slots:
              - type: text_input
                index: 4
                placeholder_key: astera.gui.loadout.rename.placeholder
                max_length: 24
                on_submit:
                  key: loadout.rename
                  payload:
                    loadout_id: "main"
        """.trimIndent()

        val spec = parser.parse(yaml, GuiYamlConfig.serializer()).toSpec()

        val slot = spec.slotAt(SlotIndex(4))
        assertThat(slot).isInstanceOf(GuiSlot.TextInput::class.java)
        slot as GuiSlot.TextInput
        assertThat(slot.placeholderKey)
            .isEqualTo(MessageKey("astera.gui.loadout.rename.placeholder"))
        assertThat(slot.maxLength).isEqualTo(24)
        assertThat(slot.onSubmitIntent.key).isEqualTo("loadout.rename")
        assertThat(slot.onSubmitIntent.payload).containsEntry("loadout_id", "main")
    }

    @Test
    fun `text_input slot YAML defaults max_length to 32 when omitted`() {
        val yaml = """
            id: example-text
            title_key: astera.gui.text.title
            size: HOPPER_5
            slots:
              - type: text_input
                index: 2
                placeholder_key: astera.gui.text.placeholder
                on_submit:
                  key: chat.alias.set
        """.trimIndent()

        val spec = parser.parse(yaml, GuiYamlConfig.serializer()).toSpec()

        val slot = spec.slotAt(SlotIndex(2)) as GuiSlot.TextInput
        assertThat(slot.maxLength).isEqualTo(32)
        assertThat(slot.onSubmitIntent.payload).isEmpty()
    }

    @Test
    fun `text_input rejects max_length above 128 at domain construction`() {
        val yaml = """
            id: bad-text-input
            title_key: astera.gui.bad.title
            size: HOPPER_5
            slots:
              - type: text_input
                index: 0
                placeholder_key: astera.gui.bad.placeholder
                max_length: 999
                on_submit:
                  key: bad.intent
        """.trimIndent()

        val config = parser.parse(yaml, GuiYamlConfig.serializer())
        assertThatIllegalArgumentException()
            .isThrownBy { config.toSpec() }
            .withMessageContaining("TextInput.maxLength")
    }
}
