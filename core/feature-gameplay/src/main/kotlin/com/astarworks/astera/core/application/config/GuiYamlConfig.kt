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

    /**
     * Static slot (Wave 4 ADR-0021, extended Wave 5h-fix2 with ClickType union
     * + Tooltip). YAML:
     * ```
     * - type: static
     *   index: 4
     *   item: iron-sword
     *   on_left:  { key: shop.purchase, payload: { offer_id: "iron-sword" } }
     *   on_right: { key: shop.preview,  payload: { offer_id: "iron-sword" } }
     *   on_shift: { key: shop.quick-buy, payload: { offer_id: "iron-sword" } }
     *   on_middle: { key: shop.favorite, payload: { offer_id: "iron-sword" } }
     *   tooltip:
     *     - astera.shop.iron-sword.tooltip.line1
     *     - astera.shop.iron-sword.tooltip.line2
     *   visible_if: permission:astera.admin.panel   # optional (ADR-0240): hidden for other viewers
     * ```
     * All click handlers + tooltip are optional. Pre-Wave 5h-fix2 YAML used
     * `on_click:` (single intent for any click) — forward-only migration:
     * rename to `on_left:` to preserve behaviour.
     */
    @Serializable
    @SerialName("static")
    public data class Static(
        override val index: Int,
        val item: String,
        @SerialName("on_left") val onLeft: GuiIntentYamlConfig? = null,
        @SerialName("on_right") val onRight: GuiIntentYamlConfig? = null,
        @SerialName("on_shift") val onShift: GuiIntentYamlConfig? = null,
        @SerialName("on_middle") val onMiddle: GuiIntentYamlConfig? = null,
        val tooltip: List<String> = emptyList(),
        val count: Int = 1,
        @SerialName("visible_if") val visibleIf: String? = null,
    ) : GuiSlotYamlConfig() {
        override fun toSlotPair(): Pair<SlotIndex, GuiSlot> =
            SlotIndex(index) to GuiSlot.Static(
                itemId = ItemId(item),
                onLeftClick = onLeft?.toIntent(),
                onRightClick = onRight?.toIntent(),
                onShiftClick = onShift?.toIntent(),
                onMiddleClick = onMiddle?.toIntent(),
                tooltip = tooltip.map { MessageKey(it) },
                count = count,
                visibleIf = visibleIf,
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
        val tooltip: List<String> = emptyList(),
    ) : GuiSlotYamlConfig() {
        override fun toSlotPair(): Pair<SlotIndex, GuiSlot> =
            SlotIndex(index) to GuiSlot.Reactive(
                binding = BindingKey(binding),
                tooltip = tooltip.map { MessageKey(it) },
            )
    }

    @Serializable
    @SerialName("empty")
    public data class Empty(
        override val index: Int,
    ) : GuiSlotYamlConfig() {
        override fun toSlotPair(): Pair<SlotIndex, GuiSlot> =
            SlotIndex(index) to GuiSlot.Empty
    }

    /**
     * Two-button confirmation prompt (Wave 5h, ADR-0079 §2). Mirrors
     * [GuiSlot.Confirmation]; see that variant's KDoc for the rendering
     * contract. YAML:
     * ```
     * - type: confirmation
     *   index: 4
     *   prompt_key: astera.gui.shop.confirm-purchase
     *   confirm_item: gui-button-confirm
     *   cancel_item: gui-button-cancel
     *   confirm:
     *     key: shop.purchase.confirm
     *     payload: { itemId: "iron-sword", qty: "1" }
     *   cancel:
     *     key: gui.close
     * ```
     */
    @Serializable
    @SerialName("confirmation")
    public data class Confirmation(
        override val index: Int,
        @SerialName("prompt_key") val promptKey: String,
        @SerialName("confirm_item") val confirmItem: String,
        @SerialName("cancel_item") val cancelItem: String,
        val confirm: GuiIntentYamlConfig,
        val cancel: GuiIntentYamlConfig,
        val tooltip: List<String> = emptyList(),
    ) : GuiSlotYamlConfig() {
        override fun toSlotPair(): Pair<SlotIndex, GuiSlot> =
            SlotIndex(index) to GuiSlot.Confirmation(
                promptKey = MessageKey(promptKey),
                confirmItemId = ItemId(confirmItem),
                cancelItemId = ItemId(cancelItem),
                confirmIntent = confirm.toIntent(),
                cancelIntent = cancel.toIntent(),
                tooltip = tooltip.map { MessageKey(it) },
            )
    }

    /**
     * Free-text input field (Wave 5h, ADR-0079 §2). Mirrors
     * [GuiSlot.TextInput]; see that variant's KDoc for the rendering
     * contract. YAML:
     * ```
     * - type: text_input
     *   index: 4
     *   placeholder_key: astera.gui.loadout.rename-placeholder
     *   max_length: 24
     *   on_submit:
     *     key: loadout.rename
     *     payload: { loadout_id: "main" }
     * ```
     * `max_length` defaults to 32 to match the domain default.
     */
    @Serializable
    @SerialName("text_input")
    public data class TextInput(
        override val index: Int,
        @SerialName("placeholder_key") val placeholderKey: String,
        @SerialName("max_length") val maxLength: Int = 32,
        @SerialName("on_submit") val onSubmit: GuiIntentYamlConfig,
    ) : GuiSlotYamlConfig() {
        override fun toSlotPair(): Pair<SlotIndex, GuiSlot> =
            SlotIndex(index) to GuiSlot.TextInput(
                placeholderKey = MessageKey(placeholderKey),
                maxLength = maxLength,
                onSubmitIntent = onSubmit.toIntent(),
            )
    }
}

/**
 * YAML-shaped DTO for the `on_left:` / `on_right:` / `on_shift:` / `on_middle:`
 * blocks inside [GuiSlotYamlConfig.Static], the `confirm:` / `cancel:` blocks
 * inside [GuiSlotYamlConfig.Confirmation], and `on_submit:` inside
 * [GuiSlotYamlConfig.TextInput].
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
