package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ARMOR_PIECE_TAG
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/items/` definition files (Wave _items-1,
 * ADR-0073 — replaces the pre-_items-1 DTO).
 *
 * Each `content/items/` YAML file is the single SSoT for an Astera item.
 * Pre-_items-1 the DTO carried just (id, material | external_key,
 * display_name_key, lore_keys); after _items-1 every per-item concern lives
 * inline so designers can edit one file instead of mirroring labels in
 * `content/languages/`.
 *
 * Konsist forbids `kotlinx.serialization` annotations from `*.domain.*`, so
 * this DTO lives in `application.config.*`. [toSpec] converts to the domain
 * [ItemSpec].
 *
 * # Wire format (vanilla)
 *
 * ```yaml
 * id: example-sword
 * material: IRON_SWORD
 * custom_model_data: 1001
 * model: sword
 * name:
 *   en: "Example Sword"
 *   ja: "サンプルソード"
 * role:
 *   en: "Melee / demo sword"
 *   ja: "近接 / テスト用の剣"
 * flavor:                  # optional
 *   en: "A demo sword."
 *   ja: "テスト用。"
 * rarity: rare
 * level_requirement: 5
 * attributes:
 *   attack_damage: 7.0
 *   attack_speed: 1.6
 * equip_slot: mainhand
 * max_durability: 250
 * unbreakable: false
 * drop_on_death: true
 * keep_on_death: false
 * enchantable: true
 * glint: null
 * skills:
 *   - trigger: right_click
 *     skill_id: fireball
 *     cooldown_override_ticks: 40
 * pdc_tags:
 *   astera:family: blade
 * ```
 *
 * # Wire format (external)
 *
 * Replace `material` (+ optional `custom_model_data`) with `external_key`.
 * Exactly one of (`material`, `external_key`) MUST be set; both / neither is
 * rejected at parse-time.
 *
 * # Compatibility
 *
 * Pre-release posture (`/IdeaProjects/Astera/.claude/CLAUDE.md §Pre-release`)
 * applies — no dual-decoder for the legacy shape, no `@Deprecated` aliases.
 * Existing YAML must be rewritten to the new schema.
 *
 * The optional `model` key (ADR-0070, Wave _infra-5) names the visual model
 * slug Studio / Paper / Bedrock adapters should resolve. It is opaque — no
 * `.model.json` suffix, no path — and `null` defers to vendor / archetype
 * fallback. Sits next to `custom_model_data` because both are render-only
 * metadata; the slug is the cross-platform replacement, customModelData stays
 * for vanilla resource-pack workflows.
 */
@Serializable
public data class ItemYamlConfig(
    public val id: String,
    public val material: String? = null,
    @SerialName("custom_model_data") public val customModelData: Int? = null,
    public val model: String? = null,
    @SerialName("external_key") public val externalKey: String? = null,
    public val name: Map<String, String> = emptyMap(),
    /** Plain GUI text (`gui-*` tiles, indicators); gear / blocks use [role] + [flavor] and get generated lines. */
    public val lore: Map<String, List<String>> = emptyMap(),
    /** One-line role under the header (`docs/contributing/item-lore-style.md`). Plain text, per locale. */
    public val role: Map<String, String> = emptyMap(),
    /** Optional one-line flavour at the bottom of the card. Plain text, per locale. */
    public val flavor: Map<String, String> = emptyMap(),
    public val rarity: String = "common",
    @SerialName("level_requirement") public val levelRequirement: Int = 1,
    public val attributes: AttributesYaml = AttributesYaml(),
    @SerialName("equip_slot") public val equipSlot: String = "mainhand",
    @SerialName("max_durability") public val maxDurability: Int? = null,
    public val unbreakable: Boolean = false,
    @SerialName("drop_on_death") public val dropOnDeath: Boolean = true,
    @SerialName("keep_on_death") public val keepOnDeath: Boolean = false,
    public val enchantable: Boolean = true,
    public val glint: Boolean? = null,
    public val skills: List<ItemSkillBindingYaml> = emptyList(),
    @SerialName("pdc_tags") public val pdcTags: Map<String, String> = emptyMap(),
    /** Field-tool identity (`tool: {class: PICKAXE, tier: STONE}`); stored as the `astera:tool` tag the mining path reads. */
    public val tool: ToolYaml? = null,
    /** Armor identity (`armor_piece: <content/armor id>`); stored as the `astera:armor_piece` tag combat reads for worn armor. */
    @SerialName("armor_piece") public val armorPiece: String? = null,
    @SerialName("equipment_asset") public val equipmentAsset: String? = null,
    // ---- Resource pool (Wave 5k, ADR-0080) ----
    /** Lowercase serial name of the resource type (`ammo` / `energy`); omit for non-weapon items. */
    @SerialName("resource_type") public val resourceType: String? = null,
    @SerialName("resource_cost_per_use") public val resourceCostPerUse: Int = 0,
    /** -1 (default) means "use the type's [com.astarworks.astera.core.domain.resource.ResourceCap] default cap". */
    @SerialName("resource_max_pool") public val resourceMaxPool: Int = -1,
    /** -1 (default) means "use the type's [com.astarworks.astera.core.domain.resource.ResourceCap] default rate". */
    @SerialName("resource_regen_per_second") public val resourceRegenPerSecond: Double = -1.0,
    @SerialName("resource_reloadable") public val resourceReloadable: Boolean = false,
) {

    /**
     * Parse the [id] into a domain [ItemId], surfacing any kebab-case
     * violation as `IllegalArgumentException` (caught by ContentLoader as a
     * failed load).
     */
    public fun toItemId(): ItemId = ItemId(id)

    /**
     * Validate the bare wire-format invariants (id parses + material xor
     * external_key). Other invariants live in the domain types' `init`
     * blocks and surface during [toSpec].
     */
    public fun validate(): ItemId {
        val itemId = toItemId()
        val hasMaterial = material != null
        val hasExternal = externalKey != null
        require(hasMaterial xor hasExternal) {
            "ItemYamlConfig '$id' must specify exactly one of `material` or `external_key`."
        }
        return itemId
    }

    /**
     * Convert this DTO to an [ItemSpec]. Performs every conversion the loader
     * needs: id parse, template variant pick, locale-key parse for inline
     * i18n maps, enum lookups for rarity / equip_slot / skill triggers,
     * sub-record materialization.
     *
     * Skill-id existence is NOT checked here — that's enforced at lookup time
     * by `ISkillRegistry` so this layer stays free of any registry
     * dependency. A typo in `skill_id` will still produce a well-formed
     * [ItemSpec] but the cast attempt will log a warning at runtime.
     */
    public fun toSpec(): ItemSpec {
        val itemId = validate()
        val template: ItemTemplate = if (externalKey != null) {
            ItemTemplate.External(externalKey)
        } else {
            // validate() guarantees material != null here.
            ItemTemplate.Vanilla(material!!, customModelData)
        }
        return ItemSpec(
            id = itemId,
            template = template,
            displayName = name.toLocalizedText(),
            lore = lore.toLocalizedLore(),
            rarity = parseRarity(rarity),
            levelRequirement = levelRequirement,
            attributes = attributes.toDomain(),
            equipSlot = parseEquipSlot(equipSlot),
            durability = DurabilityPolicy(
                maxDurability = maxDurability,
                unbreakable = unbreakable,
            ),
            dropPolicy = DropPolicy(
                dropOnDeath = dropOnDeath,
                keepOnDeath = keepOnDeath,
            ),
            enchantable = enchantable,
            glint = glint,
            skillBindings = skills.map { it.toDomain() },
            pdcTags = pdcTags +
                (if (tool == null) emptyMap() else mapOf(HitCustomBlockUseCase.TOOL_TAG to tool.toSerialKey(itemId.value))) +
                (if (armorPiece == null) emptyMap() else mapOf(ARMOR_PIECE_TAG to armorPiece)),
            modelSlug = model,
            equipmentAsset = equipmentAsset,
            resourceType = resourceType?.let {
                ResourceType.fromKey(it)
                    ?: throw IllegalArgumentException(
                        "ItemYamlConfig '$id': unknown resource_type '$it' " +
                            "(expected ammo / energy).",
                    )
            },
            resourceCostPerUse = resourceCostPerUse,
            resourceMaxPool = resourceMaxPool,
            resourceRegenPerSecond = resourceRegenPerSecond,
            resourceReloadable = resourceReloadable,
            role = role.toLocalizedText(),
            flavor = flavor.toLocalizedText(),
        )
    }

    private fun Map<String, String>.toLocalizedText(): LocalizedText =
        if (isEmpty()) LocalizedText.EMPTY
        else LocalizedText(mapKeys { (k, _) -> LocaleCode.parse(k) })

    private fun Map<String, List<String>>.toLocalizedLore(): LocalizedLore =
        if (isEmpty()) LocalizedLore.EMPTY
        else LocalizedLore(mapKeys { (k, _) -> LocaleCode.parse(k) })

    private fun parseRarity(raw: String): Rarity =
        try {
            Rarity.valueOf(raw.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("ItemYamlConfig '$id': unknown rarity '$raw' (expected one of ${Rarity.entries.joinToString { it.name.lowercase() }})", e)
        }

    private fun parseEquipSlot(raw: String): EquipSlot =
        try {
            EquipSlot.valueOf(raw.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("ItemYamlConfig '$id': unknown equip_slot '$raw' (expected one of ${EquipSlot.entries.joinToString { it.name.lowercase() }})", e)
        }
}

/**
 * Sub-DTO for inline numeric attributes on an item (Wave _items-1).
 *
 * Mirrors [ItemAttributes] one-to-one — every field is optional and rejects
 * out-of-range values at the domain layer.
 */
@Serializable
public data class AttributesYaml(
    @SerialName("attack_damage") public val attackDamage: Double? = null,
    @SerialName("attack_speed") public val attackSpeed: Double? = null,
    public val armor: Double? = null,
    @SerialName("armor_toughness") public val armorToughness: Double? = null,
    @SerialName("knockback_resistance") public val knockbackResistance: Double? = null,
    @SerialName("movement_speed_multiplier") public val movementSpeedMultiplier: Double? = null,
    @SerialName("max_health_bonus") public val maxHealthBonus: Double? = null,
) {
    public fun toDomain(): ItemAttributes = ItemAttributes(
        attackDamage = attackDamage,
        attackSpeed = attackSpeed,
        armor = armor,
        armorToughness = armorToughness,
        knockbackResistance = knockbackResistance,
        movementSpeedMultiplier = movementSpeedMultiplier,
        maxHealthBonus = maxHealthBonus,
    )
}

/**
 * Sub-DTO for one trigger → skill binding on an item (Wave _items-1).
 *
 * - `trigger` — name of a [SkillTrigger] enum value (case-insensitive).
 * - `skill_id` — kebab-case skill identifier.
 * - `cooldown_override_ticks` — optional, see
 *   [ItemSkillBinding.cooldownOverrideTicks].
 */
@Serializable
public data class ItemSkillBindingYaml(
    public val trigger: String,
    @SerialName("skill_id") public val skillId: String,
    @SerialName("cooldown_override_ticks") public val cooldownOverrideTicks: Int? = null,
) {
    public fun toDomain(): ItemSkillBinding {
        val triggerEnum = try {
            SkillTrigger.valueOf(trigger.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("ItemSkillBindingYaml: unknown trigger '$trigger'", e)
        }
        return ItemSkillBinding(
            trigger = triggerEnum,
            skillId = SkillId(skillId),
            cooldownOverrideTicks = cooldownOverrideTicks,
        )
    }
}


/** `tool:` block of an item YAML: which tool class and tier the item counts as when mining and when a skill reads it. */
@Serializable
public data class ToolYaml(
    @SerialName("class") public val toolClass: String,
    public val tier: String,
) {
    internal fun toSerialKey(itemId: String): String {
        val kind = runCatching { ToolKind.valueOf(toolClass.trim().uppercase()) }.getOrNull()
            ?.takeIf { it != ToolKind.NONE }
            ?: throw IllegalArgumentException("ItemYamlConfig '$itemId': unknown tool.class '$toolClass' (expected PICKAXE / AXE / SHOVEL)")
        val tierValue = runCatching { ToolTier.valueOf(tier.trim().uppercase()) }.getOrNull()
            ?.takeIf { it != ToolTier.NONE }
            ?: throw IllegalArgumentException("ItemYamlConfig '$itemId': unknown tool.tier '$tier' (expected WOOD / STONE / IRON / DIAMOND / NETHERITE)")
        return ToolType(kind, tierValue).serialKey()
    }
}
