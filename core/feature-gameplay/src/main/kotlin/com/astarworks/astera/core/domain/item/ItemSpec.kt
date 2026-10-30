package com.astarworks.astera.core.domain.item

import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.resource.ResourceType

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
 * # Resource pool (Wave 5k, ADR-0080, Annihilation vision)
 *
 * Weapons declare a [resourceType] (`Ammo`/`Stamina`/`Mana`) so the
 * `IResourcePoolService` knows what counter to deplete on skill cast.
 * Non-weapon items (potions, GUI panes, indicators) leave [resourceType] =
 * `null` and the remaining resource fields stay at their defaults.
 *
 *   - [resourceCostPerUse]: how many units to subtract per cast / swing.
 *   - [resourceMaxPool]: per-weapon override of the per-type
 *     [com.astarworks.astera.core.domain.resource.ResourceCap.maxPool]
 *     default. Non-positive value means "use the type's default cap".
 *   - [resourceRegenPerSecond]: per-weapon override of the per-type
 *     [com.astarworks.astera.core.domain.resource.ResourceCap.regenPerSecond]
 *     default. Negative value means "use the type's default rate".
 *   - [resourceReloadable]: only meaningful for `Ammo` — gates the
 *     `IResourcePoolService.reload` call. Other types reject reload
 *     unconditionally.
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
    /** Resource-pack equipment asset in the Astera namespace. */
    val equipmentAsset: String? = null,
    // ---- Resource pool (Wave 5k, ADR-0080) ----
    val resourceType: ResourceType? = null,
    val resourceCostPerUse: Int = 0,
    val resourceMaxPool: Int = -1,
    val resourceRegenPerSecond: Double = -1.0,
    val resourceReloadable: Boolean = false,
    /** One-line "what is this for" under the header (docs/contributing/item-lore-style.md). Plain text. */
    val role: LocalizedText = LocalizedText.EMPTY,
    /** Optional one-line flavour text at the bottom of the card. Plain text. */
    val flavor: LocalizedText = LocalizedText.EMPTY,
    /** Weapon hold-table entry shown as one generated tooltip row; `null` = no row (not a weapon). */
    val hold: ItemHold? = null,
) {
    init {
        require(equipmentAsset == null || equipmentAsset.matches(Regex("[a-z0-9._-]+"))) {
            "ItemSpec.equipmentAsset must be a resource asset id"
        }
        require(equipmentAsset == null || equipSlot in setOf(EquipSlot.HELMET, EquipSlot.CHESTPLATE, EquipSlot.LEGGINGS, EquipSlot.BOOTS)) {
            "ItemSpec.equipmentAsset requires an armor equip_slot"
        }
        require(levelRequirement >= 1) {
            "ItemSpec.levelRequirement must be >= 1, got $levelRequirement"
        }
        require(resourceCostPerUse >= 0) {
            "ItemSpec.resourceCostPerUse must be >= 0, got $resourceCostPerUse"
        }
    }
}
