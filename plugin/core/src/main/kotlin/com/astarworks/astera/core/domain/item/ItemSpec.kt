package com.astarworks.astera.core.domain.item

import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.world.BlockType

/**
 * Materialized item definition (Wave _items-1, ADR-0073).
 *
 * Replaces the legacy split of `WeaponSpec` + `IMcItemTemplate` + scattered
 * `MessageKey` lookups: every per-item concern lives on this one record, so
 * the YAML loader produces an `ItemSpec` directly via
 * [com.astarworks.astera.core.application.config.ItemYamlConfig.toSpec] and
 * `IItemRegistry` returns it to every caller (give command, GUI, loadout,
 * shop, combat skill resolver).
 *
 * # Skill bindings
 *
 * [skillBindings] is the canonical "what this item does when pressed". Combat
 * subscribes to player input adapters and looks up the binding by `(itemId,
 * trigger)` — see `SkillCastRequested` in `core.domain.event`.
 *
 * # Why inline i18n
 *
 * [displayName] and [lore] are inline [LocalizedText] / [LocalizedLore] so
 * `content/items/<id>.yaml` can declare both languages in one place. Designers
 * don't have to mirror the value to `content/languages/{en,ja}.yaml`. This
 * supersedes the pre-Wave-_items-1 `displayNameKey` / `loreKey: MessageKey`
 * shape.
 *
 * # Invariants
 *
 *   - [levelRequirement] >= 1 (validated here).
 *   - [ItemSkillBinding.cooldownOverrideTicks] >= 0 when set (validated in
 *     `ItemSkillBinding.init`).
 *   - Sub-records may impose their own (e.g., [DropPolicy] mutual exclusion).
 *
 * The `init` block is intentionally minimal — most validation lives in the
 * leaf value objects so they stay safe even when constructed outside
 * `ItemSpec`.
 *
 * # Model slug (ADR-0070, Wave _infra-5)
 *
 * [modelSlug] is an opaque identifier consumed by Studio's `/items` preview
 * and by future Paper / Bedrock model-resolution adapters. `null` means
 * "no override — fall back to the vendor default or archetype-derived model".
 * The slug has no `.model.json` suffix; resolution semantics are owned by the
 * consumer.
 *
 * # Placeable as custom block (Wave 5x, ADR-0090)
 *
 * [placeableAs] turns an item into a placement source for a
 * [com.astarworks.astera.core.domain.block.CustomBlockSpec]. When set, the
 * Paper place-handler (Wave 6b) looks up the spec via
 * `ICustomBlockRegistry.resolve(placeableAs)` and routes to
 * `IBlockStateGateway.placeCustomBlock`. `null` keeps the item non-placeable
 * (vanilla item behavior).
 */
public data class ItemSpec(
    val id: ItemId,
    val template: ItemTemplate,
    val displayName: LocalizedText,
    val lore: LocalizedLore,
    val rarity: Rarity,
    val levelRequirement: Int,
    val attributes: ItemAttributes,
    val equipSlot: EquipSlot,
    val durability: DurabilityPolicy,
    val dropPolicy: DropPolicy,
    val enchantable: Boolean,
    val glint: Boolean?,
    val skillBindings: List<ItemSkillBinding>,
    val pdcTags: Map<String, String>,
    val modelSlug: String? = null,
    val placeableAs: BlockType? = null,
) {
    init {
        require(levelRequirement >= 1) {
            "ItemSpec.levelRequirement must be >= 1, got $levelRequirement"
        }
    }
}
