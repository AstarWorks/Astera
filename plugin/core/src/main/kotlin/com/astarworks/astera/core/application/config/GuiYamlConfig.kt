package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/gui/` definition files (Wave 4 R3, ADR-0021).
 *
 * Kept separate from [GuiSpec] so the domain stays free of serialization
 * annotations (Konsist enforces this; see `principles.md` §6 and ADR-0016).
 * The wire format is decided by `ContentLoader` per ADR-0016; the polymorphic
 * discriminator key for [GuiSlotYamlConfig] is `type` and matches kaml's
 * `PolymorphismStyle.Property` configuration so the same DTO works across
 * formats (YAML / TOML / JSON).
 *
 * `size` accepts the [GuiSize] enum names (case-insensitive): `chest_27`,
 * `hopper_5`, etc.
 */
@Serializable
public data class GuiYamlConfig(
    val id: String,
    @SerialName("title_key") val titleKey: String,
    val size: String,
    val slots: List<GuiSlotYamlConfig> = emptyList(),
) {
    public fun toSpec(): GuiSpec =
        GuiSpec.build(
            id = GuiId(id),
            title = MessageKey(titleKey),
            size = GuiSize.valueOf(size.uppercase()),
        ) {
            slots.forEach { slotYamlConfig ->
                val slotPair = slotYamlConfig.toSlotPair()
                slot(slotPair.first, slotPair.second)
            }
        }
}

/**
 * Typed slot payload, matching the sealed [GuiSlot] hierarchy.
 *
 * Polymorphic by the `type:` discriminator. Each variant carries only the
 * fields its kind needs, mirroring the domain so the YAML reads naturally to
 * anyone familiar with [GuiSlot].
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class GuiSlotYamlConfig {

    public abstract val index: Int

    public abstract fun toSlotPair(): Pair<SlotIndex, GuiSlot>

    @Serializable
    @SerialName("static")
    public data class Static(
        override val index: Int,
        val item: String,
        @SerialName("on_click") val onClick: GuiIntentYamlConfig? = null,
        val count: Int = 1,
    ) : GuiSlotYamlConfig() {
        override fun toSlotPair(): Pair<SlotIndex, GuiSlot> =
            SlotIndex(index) to GuiSlot.Static(
                itemId = ItemId(item),
                onClick = onClick?.toIntent(),
                count = count,
            )
    }

    @Serializable
    @SerialName("border")
    public data class Border(
        override val index: Int,
        val item: String,
    ) : GuiSlotYamlConfig() {
        override fun toSlotPair(): Pair<SlotIndex, GuiSlot> =
            SlotIndex(index) to GuiSlot.Border(ItemId(item))
    }

    @Serializable
    @SerialName("reactive")
    public data class Reactive(
        override val index: Int,
        val binding: String,
    ) : GuiSlotYamlConfig() {
        override fun toSlotPair(): Pair<SlotIndex, GuiSlot> =
            SlotIndex(index) to GuiSlot.Reactive(BindingKey(binding))
    }

    @Serializable
    @SerialName("empty")
    public data class Empty(
        override val index: Int,
    ) : GuiSlotYamlConfig() {
        override fun toSlotPair(): Pair<SlotIndex, GuiSlot> =
            SlotIndex(index) to GuiSlot.Empty
    }
}

/**
 * YAML-shaped DTO for the `on_click:` block inside a [GuiSlotYamlConfig.Static].
 *
 * Maps 1:1 to the domain [GuiIntent]. Kept separate so the domain stays free of
 * serialization annotations.
 */
@Serializable
public data class GuiIntentYamlConfig(
    val key: String,
    val payload: Map<String, String> = emptyMap(),
) {
    public fun toIntent(): GuiIntent = GuiIntent(key, payload)
}
