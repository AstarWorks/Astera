package com.astarworks.astera.core.application.config.block

import com.astarworks.astera.core.domain.block.BlockDrop
import com.astarworks.astera.core.domain.block.CustomBlockSpec
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/blocks/<id>.yaml` files (Wave 5x, ADR-0090).
 *
 * Lives in `application/config/` so [CustomBlockSpec] stays free of
 * `kotlinx.serialization` annotations (per `coding-posture.md` / Konsist).
 * The DTO maps 1:1 to the on-disk YAML; [toSpec] performs the enum / range
 * validation that surfaces authoring mistakes at load time.
 *
 * # Wire format
 *
 * ```yaml
 * id: astera:hardened_soil
 * display_name:
 *   en: "Hardened Soil"
 *   ja: "硬化土"
 * base_bukkit_material: DIRT
 * material: SOIL
 * base_durability: 5000
 * tool_effectiveness:
 *   "PICKAXE/WOOD": 1
 *   "PICKAXE/STONE": 10
 *   "PICKAXE/IRON": 50
 *   "PICKAXE/DIAMOND": 200
 * drops:
 *   - item_id: hardened-soil-block
 *     count_range: "1..1"
 *     drop_chance: 1.0
 *     requires_tool: "PICKAXE/IRON"
 * placed_from: hardened-soil-block
 * immune_to: []
 * texture: hardened-soil
 * ```
 */
@Serializable
public data class CustomBlockYamlConfig(
    public val id: String,
    @SerialName("display_name") public val displayName: Map<String, String> = emptyMap(),
    @SerialName("base_bukkit_material") public val baseBukkitMaterial: String,
    public val material: String,
    @SerialName("base_durability") public val baseDurability: Int,
    @SerialName("tool_effectiveness") public val toolEffectiveness: Map<String, Int> = emptyMap(),
    public val drops: List<BlockDropYamlConfig> = emptyList(),
    @SerialName("placed_from") public val placedFrom: String? = null,
    @SerialName("immune_to") public val immuneTo: List<String> = emptyList(),
    public val texture: String? = null,
) {
    /**
     * Convert the DTO into a [CustomBlockSpec]. Throws
     * [IllegalArgumentException] on enum / range violations; `ContentLoader`
     * catches and counts these as failed loads, surfaced via the load report.
     */
    public fun toSpec(): CustomBlockSpec {
        val blockType = BlockType(id)
        val materialEnum = parseMaterial(material, field = "material")
        val toolMatrix: Map<ToolType, Int>? =
            if (toolEffectiveness.isEmpty()) {
                null
            } else {
                buildMap {
                    for ((rawKey, value) in toolEffectiveness) {
                        val tool = ToolType.fromSerialKey(rawKey)
                            ?: throw IllegalArgumentException(
                                "CustomBlockYamlConfig[$id].tool_effectiveness: invalid tool key '$rawKey' " +
                                    "(expected NONE or KIND/TIER, e.g. PICKAXE/IRON)",
                            )
                        require(value in 0..MAX_TOOL_CELL) {
                            "CustomBlockYamlConfig[$id].tool_effectiveness['$rawKey']: " +
                                "value must be in 0..$MAX_TOOL_CELL, got $value"
                        }
                        put(tool, value)
                    }
                }
            }
        val immune = immuneTo.map { parseMaterial(it, field = "immune_to[$it]") }.toSet()
        val dropSpecs = drops.map { it.toDomain(blockType) }
        return CustomBlockSpec(
            id = blockType,
            displayName = displayName.toLocalizedText(),
            baseBukkitMaterial = baseBukkitMaterial,
            material = materialEnum,
            baseDurability = baseDurability,
            toolEffectiveness = toolMatrix,
            drops = dropSpecs,
            placedFrom = placedFrom?.let { ItemId(it) },
            immuneTo = immune,
            texture = texture,
        )
    }

    private fun parseMaterial(raw: String, field: String): BlockMaterial =
        runCatching { BlockMaterial.valueOf(raw.uppercase()) }.getOrElse {
            throw IllegalArgumentException(
                "CustomBlockYamlConfig[$id].$field: unknown BlockMaterial '$raw' " +
                    "(accepted: ${BlockMaterial.entries.map { it.name.lowercase() }})",
            )
        }

    private fun Map<String, String>.toLocalizedText(): LocalizedText =
        if (isEmpty()) LocalizedText.EMPTY
        else LocalizedText(mapKeys { (k, _) -> LocaleCode.parse(k) })

    public companion object {
        /**
         * Per-cell upper bound for [toolEffectiveness] (same defensive cap as
         * `BlockDurabilityYamlConfig.MAX_DAMAGE`).
         */
        public const val MAX_TOOL_CELL: Int = 1000

        /**
         * Cartesian product of the legal `KIND/TIER` strings, surfaced in
         * error messages so authors see exactly what's accepted.
         */
        @Suppress("unused")
        public val ACCEPTED_TOOL_KEYS: List<String> = buildList {
            add("NONE")
            for (kind in ToolKind.entries) {
                if (kind == ToolKind.NONE) continue
                for (tier in ToolTier.entries) {
                    if (tier == ToolTier.NONE) continue
                    add("${kind.name}/${tier.name}")
                }
            }
        }
    }
}

/**
 * Sub-DTO for an entry in the parent [CustomBlockYamlConfig.drops].
 *
 * `count_range` is a `"min..max"` string (so YAML keeps a single scalar
 * shape instead of nested mapping). `requires_tool` reuses the same
 * `ToolType.fromSerialKey` convention as the top-level
 * [CustomBlockYamlConfig.toolEffectiveness].
 */
@Serializable
public data class BlockDropYamlConfig(
    @SerialName("item_id") public val itemId: String,
    @SerialName("count_range") public val countRange: String = "1..1",
    @SerialName("drop_chance") public val dropChance: Float = 1f,
    @SerialName("requires_tool") public val requiresTool: String? = null,
) {
    public fun toDomain(parent: BlockType): BlockDrop {
        val range = parseRange(countRange, parent)
        val tool = requiresTool?.let { raw ->
            ToolType.fromSerialKey(raw)
                ?: throw IllegalArgumentException(
                    "BlockDropYamlConfig in $parent: invalid requires_tool '$raw'",
                )
        }
        return BlockDrop(
            itemId = ItemId(itemId),
            countRange = range,
            dropChance = dropChance,
            requiresTool = tool,
        )
    }

    private fun parseRange(raw: String, parent: BlockType): IntRange {
        // Allow "3" as shorthand for "3..3".
        val trimmed = raw.trim()
        if (".." !in trimmed) {
            val single = trimmed.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "BlockDropYamlConfig in $parent: invalid count_range '$raw' " +
                        "(expected 'min..max' or single integer)",
                )
            return single..single
        }
        val parts = trimmed.split("..")
        if (parts.size != 2) {
            throw IllegalArgumentException(
                "BlockDropYamlConfig in $parent: malformed count_range '$raw' " +
                    "(expected 'min..max')",
            )
        }
        val min = parts[0].trim().toIntOrNull()
            ?: throw IllegalArgumentException(
                "BlockDropYamlConfig in $parent: count_range min '${parts[0]}' is not an integer",
            )
        val max = parts[1].trim().toIntOrNull()
            ?: throw IllegalArgumentException(
                "BlockDropYamlConfig in $parent: count_range max '${parts[1]}' is not an integer",
            )
        require(min <= max) {
            "BlockDropYamlConfig in $parent: count_range min ($min) must be <= max ($max)"
        }
        return min..max
    }
}
