package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.world.BlockDurabilityRule
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.ToolType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/balance/block-durability.yaml` (Wave 5d).
 *
 * Lives in `application/config/` so [BlockDurabilityRule] (domain) stays free
 * of `kotlinx.serialization` annotations (per `coding-posture.md` and
 * `ArchitectureTest` Konsist rule). The DTO maps 1:1 to the on-disk YAML;
 * [toDomain] performs the enum / range validation that surfaces authoring
 * mistakes at load time rather than at first hit.
 *
 * # Wire format
 *
 * ```yaml
 * materials:
 *   "minecraft:stone": stone
 *   "minecraft:oak_log": wood
 *
 * damage_matrix:
 *   wood:
 *     stone: 25
 *     soil: 100
 *   iron:
 *     stone: 100
 * ```
 *
 * The outer keys of `damage_matrix` are lower-case [ToolType] names
 * (`none`, `wood`, `stone`, `iron`, `diamond`); the inner keys are lower-case
 * [BlockMaterial] names (`wood`, `stone`, `soil`, `other`). Authors may
 * partially specify the matrix; missing cells fall back to
 * [BlockDurabilityRule.DEFAULT_DAMAGE] at lookup time.
 *
 * # Validation
 *
 * - Unknown tool / material enum strings → [ConfigError.UnknownEnumValue].
 * - Damage values outside `0..MAX_DAMAGE` → [ConfigError.OutOfRange]. The
 *   cap is defensive — a 100-HP block with a >1000 damage value indicates
 *   an authoring slip, not a real intent.
 * - Material binding values follow the same enum rule as the matrix.
 */
@Serializable
public data class BlockDurabilityYamlConfig(
    public val materials: Map<String, String> = emptyMap(),
    @SerialName("damage_matrix") public val damageMatrix: Map<String, Map<String, Int>> = emptyMap(),
) {
    /**
     * Convert the parsed DTO into a [BlockDurabilityRule].
     *
     * Returns `Result.Failure(ConfigError)` on any enum / range violation per
     * the `coding-posture.md` "no raw JVM throwables in application API" rule.
     * The first failure short-circuits — authors fix one mistake at a time
     * (consistent with the rest of Astera's YAML loaders).
     */
    public fun toDomain(): Result<BlockDurabilityRule, ConfigError> {
        val bindings = mutableMapOf<String, BlockMaterial>()
        for ((blockKey, materialRaw) in materials) {
            val parsed = parseMaterial(materialRaw, field = "materials[\"$blockKey\"]")
            when (parsed) {
                is Result.Success -> bindings[blockKey] = parsed.value
                is Result.Failure -> return Result.failure(parsed.error)
            }
        }

        val matrix = mutableMapOf<Pair<ToolType, BlockMaterial>, Int>()
        for ((toolRaw, row) in damageMatrix) {
            val parsedTool = parseTool(toolRaw, field = "damage_matrix.$toolRaw")
            val tool = when (parsedTool) {
                is Result.Success -> parsedTool.value
                is Result.Failure -> return Result.failure(parsedTool.error)
            }
            for ((materialRaw, damage) in row) {
                val parsedMaterial =
                    parseMaterial(materialRaw, field = "damage_matrix.$toolRaw.$materialRaw")
                val material = when (parsedMaterial) {
                    is Result.Success -> parsedMaterial.value
                    is Result.Failure -> return Result.failure(parsedMaterial.error)
                }
                if (damage !in 0..MAX_DAMAGE) {
                    return Result.failure(
                        ConfigError.OutOfRange(
                            field = "damage_matrix.$toolRaw.$materialRaw",
                            rawValue = damage.toString(),
                            constraint = "must be in 0..$MAX_DAMAGE",
                        ),
                    )
                }
                matrix[tool to material] = damage
            }
        }

        return Result.success(
            BlockDurabilityRule(
                matrix = matrix.toMap(),
                materialBindings = bindings.toMap(),
            ),
        )
    }

    private fun parseTool(raw: String, field: String): Result<ToolType, ConfigError> =
        runCatching { ToolType.valueOf(raw.uppercase()) }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Result.failure(
                    ConfigError.UnknownEnumValue(
                        field = field,
                        rawValue = raw,
                        accepted = ToolType.entries.map { it.name.lowercase() },
                    ),
                )
            },
        )

    private fun parseMaterial(raw: String, field: String): Result<BlockMaterial, ConfigError> =
        runCatching { BlockMaterial.valueOf(raw.uppercase()) }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Result.failure(
                    ConfigError.UnknownEnumValue(
                        field = field,
                        rawValue = raw,
                        accepted = BlockMaterial.entries.map { it.name.lowercase() },
                    ),
                )
            },
        )

    public companion object {
        /**
         * Upper bound for a single damage_matrix cell. A 100-HP block tops
         * out at 100 in practice; the 1000 cap is defensive headroom that
         * still catches obvious authoring slips (a trailing zero).
         */
        public const val MAX_DAMAGE: Int = 1000
    }
}
