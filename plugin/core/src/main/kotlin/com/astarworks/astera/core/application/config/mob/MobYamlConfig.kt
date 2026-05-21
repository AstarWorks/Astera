package com.astarworks.astera.core.application.config.mob

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.entity.HologramKind
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobAiHint
import com.astarworks.astera.core.domain.mob.MobAppearance
import com.astarworks.astera.core.domain.mob.MobNameTagColor
import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.MobSkillBinding
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.SpawnConditions
import com.astarworks.astera.core.domain.skill.SkillId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/mobs/<id>.yaml` (Wave 5pve, ADR-0085).
 *
 * Konsist forbids `kotlinx.serialization` annotations in `*.domain.*`, so
 * this DTO lives in `application.config.mob.*`. [toSpec] converts to the
 * domain [MobSpec].
 *
 * # Wire format
 *
 * ```yaml
 * id: astera:forest-wolf
 * display_name:
 *   en: "Forest Wolf"
 *   ja: "森狼"
 * base_entity_type: MOB
 * attributes:
 *   mob.max_hp: 25.0
 *   mob.damage: 4.0
 *   mob.speed: 1.2
 * appearance:
 *   display_kind:
 *     type: item
 *     item_id: astera:wolf-model
 *   custom_name_visible: true
 *   name_tag_color: { r: 200, g: 200, b: 200 }
 *   glowing: false
 * equipment:
 *   - slot: mainhand
 *     item_id: astera:wolf-tooth
 * skill_bindings:
 *   - type: on_player_near
 *     skill_id: wolf-bite
 *     range: 5.0
 *     cooldown_ticks: 40
 *   - type: on_hp_threshold
 *     skill_id: wolf-howl
 *     percent: 0.3
 * loot_table: astera:forest-wolf-drops
 * spawn_conditions:
 *   min_light_level: 0
 *   max_light_level: 15
 *   biome_keys: ["minecraft:forest", "minecraft:dark_forest"]
 *   region_id: forest-edge
 *   time_of_day_range: [0, 14000]
 * ai: pathfind
 * ```
 *
 * # Validation
 *
 * The DTO carries raw strings / numerics; [toSpec] performs domain
 * validation (id regex, attribute key shape, skill binding invariants).
 * Any failure throws `IllegalArgumentException`, which `ContentLoader`
 * catches and reports as a failed file load — the rest of the registry
 * stays intact.
 */
@Serializable
public data class MobYamlConfig(
    public val id: String,
    @SerialName("display_name") public val displayName: Map<String, String> = emptyMap(),
    @SerialName("base_entity_type") public val baseEntityType: String = "MOB",
    public val attributes: Map<String, Double> = emptyMap(),
    public val appearance: MobAppearanceYaml = MobAppearanceYaml(),
    public val equipment: List<MobEquipmentYaml> = emptyList(),
    @SerialName("skill_bindings") public val skillBindings: List<MobSkillBindingYaml> = emptyList(),
    @SerialName("loot_table") public val lootTable: String = LootTableId.EMPTY.value,
    @SerialName("spawn_conditions") public val spawnConditions: SpawnConditionsYaml = SpawnConditionsYaml(),
    public val ai: String = "default",
) {

    /**
     * Convert this DTO to the domain [MobSpec]. Throws
     * `IllegalArgumentException` on any invariant failure; the loader
     * catches + reports the file as failed without affecting other entries.
     */
    public fun toSpec(): MobSpec {
        val mobId = MobTypeId(id)
        val name = LocalizedText(
            byLocale = displayName.entries.associate { (locale, text) ->
                LocaleCode(locale) to text
            },
        )
        val category = parseEntityCategory(baseEntityType, mobId)
        val attrContainer = SimpleAttributeContainer().apply {
            attributes.forEach { (key, value) ->
                set(AttributeKey(key), AttributeValue.NumberValue(value))
            }
        }
        return MobSpec(
            id = mobId,
            displayName = name,
            baseEntityType = category,
            attributes = attrContainer,
            appearance = appearance.toDomain(mobId),
            equipment = equipment.map { it.toDomain(mobId) },
            skillBindings = skillBindings.map { it.toDomain(mobId) },
            lootTable = LootTableId(lootTable),
            spawnConditions = spawnConditions.toDomain(mobId),
            ai = parseAi(ai, mobId),
        )
    }

    private fun parseEntityCategory(raw: String, mobId: MobTypeId): EntityCategory {
        return try {
            EntityCategory.valueOf(raw.uppercase())
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException(
                "MobYamlConfig($mobId): unknown base_entity_type '$raw'; " +
                    "expected one of ${EntityCategory.entries.map { it.name }}",
                ex,
            )
        }
    }

    private fun parseAi(raw: String, mobId: MobTypeId): MobAiHint {
        return MobAiHint.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "MobYamlConfig($mobId): unknown ai '$raw'; " +
                    "expected one of ${MobAiHint.entries.map { it.name.lowercase() }}",
            )
    }
}

/** Appearance DTO. See [MobYamlConfig] class KDoc for wire shape. */
@Serializable
public data class MobAppearanceYaml(
    @SerialName("display_kind") public val displayKind: DisplayKindYaml? = null,
    @SerialName("custom_name_visible") public val customNameVisible: Boolean = false,
    @SerialName("name_tag_color") public val nameTagColor: NameTagColorYaml? = null,
    public val glowing: Boolean = false,
) {
    public fun toDomain(mobId: MobTypeId): MobAppearance =
        MobAppearance(
            displayKind = displayKind?.toDomain(mobId),
            customNameVisible = customNameVisible,
            nameTagColor = nameTagColor?.toDomain(),
            glowing = glowing,
        )
}

/** Discriminated-union DTO for [HologramKind]. `type` selects the variant. */
@Serializable
public data class DisplayKindYaml(
    public val type: String,
    @SerialName("message_key") public val messageKey: String? = null,
    @SerialName("item_id") public val itemId: String? = null,
    @SerialName("block_key") public val blockKey: String? = null,
) {
    public fun toDomain(mobId: MobTypeId): HologramKind {
        return when (type.lowercase()) {
            "text" -> HologramKind.Text(
                MessageKey(
                    requireNotNull(messageKey) {
                        "MobYamlConfig($mobId).appearance.display_kind.type=text requires message_key"
                    },
                ),
            )
            "item" -> HologramKind.Item(
                ItemId(
                    requireNotNull(itemId) {
                        "MobYamlConfig($mobId).appearance.display_kind.type=item requires item_id"
                    },
                ),
            )
            "block" -> HologramKind.Block(
                requireNotNull(blockKey) {
                    "MobYamlConfig($mobId).appearance.display_kind.type=block requires block_key"
                },
            )
            else -> throw IllegalArgumentException(
                "MobYamlConfig($mobId).appearance.display_kind.type='$type' unknown; " +
                    "expected text / item / block.",
            )
        }
    }
}

@Serializable
public data class NameTagColorYaml(
    public val r: Int,
    public val g: Int,
    public val b: Int,
) {
    public fun toDomain(): MobNameTagColor = MobNameTagColor(r, g, b)
}

@Serializable
public data class MobEquipmentYaml(
    public val slot: String,
    @SerialName("item_id") public val itemId: String,
) {
    public fun toDomain(mobId: MobTypeId): Pair<EquipSlot, ItemId> {
        val parsedSlot = EquipSlot.entries.firstOrNull { it.name.equals(slot, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "MobYamlConfig($mobId).equipment slot='$slot' unknown; " +
                    "expected one of ${EquipSlot.entries.map { it.name.lowercase() }}",
            )
        return parsedSlot to ItemId(itemId)
    }
}

/**
 * Discriminated-union DTO for [MobSkillBinding]. `type` selects the variant.
 *
 * Per-variant fields are read conditionally; missing required fields surface
 * as `IllegalArgumentException` in [toDomain].
 */
@Serializable
public data class MobSkillBindingYaml(
    public val type: String,
    @SerialName("skill_id") public val skillId: String,
    @SerialName("trigger_chance") public val triggerChance: Float? = null,
    public val range: Double? = null,
    @SerialName("cooldown_ticks") public val cooldownTicks: Int? = null,
    public val percent: Float? = null,
    @SerialName("interval_ticks") public val intervalTicks: Int? = null,
) {
    public fun toDomain(mobId: MobTypeId): MobSkillBinding {
        val sid = SkillId(skillId)
        return when (type.lowercase()) {
            "on_damaged" -> MobSkillBinding.OnDamaged(
                skillId = sid,
                triggerChance = triggerChance ?: 1f,
            )
            "on_player_near" -> MobSkillBinding.OnPlayerNear(
                skillId = sid,
                range = requireNotNull(range) {
                    "MobYamlConfig($mobId).skill_bindings.on_player_near requires range"
                },
                cooldownTicks = requireNotNull(cooldownTicks) {
                    "MobYamlConfig($mobId).skill_bindings.on_player_near requires cooldown_ticks"
                },
            )
            "on_hp_threshold" -> MobSkillBinding.OnHpThreshold(
                skillId = sid,
                percent = requireNotNull(percent) {
                    "MobYamlConfig($mobId).skill_bindings.on_hp_threshold requires percent"
                },
            )
            "on_death" -> MobSkillBinding.OnDeath(skillId = sid)
            "periodic" -> MobSkillBinding.Periodic(
                skillId = sid,
                intervalTicks = requireNotNull(intervalTicks) {
                    "MobYamlConfig($mobId).skill_bindings.periodic requires interval_ticks"
                },
            )
            else -> throw IllegalArgumentException(
                "MobYamlConfig($mobId).skill_bindings type='$type' unknown; " +
                    "expected one of on_damaged / on_player_near / on_hp_threshold / on_death / periodic",
            )
        }
    }
}

@Serializable
public data class SpawnConditionsYaml(
    @SerialName("min_light_level") public val minLightLevel: Int? = null,
    @SerialName("max_light_level") public val maxLightLevel: Int? = null,
    @SerialName("biome_keys") public val biomeKeys: List<String>? = null,
    @SerialName("region_id") public val regionId: String? = null,
    @SerialName("time_of_day_range") public val timeOfDayRange: List<Long>? = null,
) {
    public fun toDomain(mobId: MobTypeId): SpawnConditions {
        val parsedRange = timeOfDayRange?.let { range ->
            require(range.size == 2) {
                "MobYamlConfig($mobId).spawn_conditions.time_of_day_range must be a 2-element list [start, end]"
            }
            range[0]..range[1]
        }
        return SpawnConditions(
            minLightLevel = minLightLevel,
            maxLightLevel = maxLightLevel,
            biomeKeys = biomeKeys?.toSet(),
            regionId = regionId?.let { MobRegionId(it) },
            timeOfDayRange = parsedRange,
        )
    }
}
