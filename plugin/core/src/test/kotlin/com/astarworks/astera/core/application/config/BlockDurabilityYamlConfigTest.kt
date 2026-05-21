package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the `Result<BlockDurabilityRule, ConfigError>` contract for
 * [BlockDurabilityYamlConfig.toDomain] (Wave 5d → Wave 5x, ADR-0090).
 *
 * Wave 5x renamed the tool axis: `wood / iron / …` → `PICKAXE/WOOD,
 * AXE/IRON, …`. Authors must see typed failures, not raw JVM throwables
 * (per `coding-posture.md` §"Application API surface").
 */
internal class BlockDurabilityYamlConfigTest {

    private fun validDto() = BlockDurabilityYamlConfig(
        materials = mapOf(
            "minecraft:stone" to "stone",
            "minecraft:oak_log" to "wood",
        ),
        damageMatrix = mapOf(
            "PICKAXE/WOOD" to mapOf("stone" to 25, "soil" to 0),
            "PICKAXE/IRON" to mapOf("stone" to 100),
            "AXE/IRON" to mapOf("wood" to 100),
            "SHOVEL/IRON" to mapOf("soil" to 100),
            "NONE" to mapOf("soil" to 10),
        ),
    )

    @Test
    fun `valid DTO converts to BlockDurabilityRule with parsed cells and bindings`() {
        val result = validDto().toDomain()
        assertThat(result).isInstanceOf(Result.Success::class.java)

        val rule = (result as Result.Success).value
        assertThat(rule.damageFor(ToolType.WOODEN_PICKAXE, BlockMaterial.STONE)).isEqualTo(25)
        assertThat(rule.damageFor(ToolType.IRON_PICKAXE, BlockMaterial.STONE)).isEqualTo(100)
        assertThat(rule.damageFor(ToolType.IRON_AXE, BlockMaterial.WOOD)).isEqualTo(100)
        assertThat(rule.damageFor(ToolType.IRON_SHOVEL, BlockMaterial.SOIL)).isEqualTo(100)
        assertThat(rule.damageFor(ToolType.NONE, BlockMaterial.SOIL)).isEqualTo(10)

        // material bindings round-trip
        assertThat(rule.materialOf(BlockType("minecraft:stone"))).isEqualTo(BlockMaterial.STONE)
        assertThat(rule.materialOf(BlockType("minecraft:oak_log"))).isEqualTo(BlockMaterial.WOOD)
        // unknown block falls back to OTHER per BlockDurabilityRule contract
        assertThat(rule.materialOf(BlockType("unknown:foo"))).isEqualTo(BlockMaterial.OTHER)
    }

    @Test
    fun `empty DTO converts to an empty but valid rule`() {
        val result = BlockDurabilityYamlConfig().toDomain()
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val rule = (result as Result.Success).value
        // Empty matrix falls back to DEFAULT_DAMAGE
        assertThat(rule.damageFor(ToolType.WOODEN_AXE, BlockMaterial.SOIL))
            .isEqualTo(com.astarworks.astera.core.domain.world.BlockDurabilityRule.DEFAULT_DAMAGE)
    }

    @Test
    fun `unknown tool key returns UnknownEnumValue failure`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("HAMMER/PLATINUM" to mapOf("stone" to 50)),
        )
        val err = (dto.toDomain() as Result.Failure).error
        assertThat(err).isInstanceOf(ConfigError.UnknownEnumValue::class.java)
        err as ConfigError.UnknownEnumValue
        assertThat(err.field).isEqualTo("damage_matrix[\"HAMMER/PLATINUM\"]")
        assertThat(err.rawValue).isEqualTo("HAMMER/PLATINUM")
        assertThat(err.accepted).contains("NONE", "PICKAXE/WOOD", "AXE/IRON", "SHOVEL/DIAMOND")
    }

    @Test
    fun `malformed tool key without slash returns UnknownEnumValue failure`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("wood" to mapOf("stone" to 25)),
        )
        val err = (dto.toDomain() as Result.Failure).error as ConfigError.UnknownEnumValue
        assertThat(err.rawValue).isEqualTo("wood")
    }

    @Test
    fun `unknown material name in matrix returns UnknownEnumValue failure`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("PICKAXE/WOOD" to mapOf("glass" to 25)),
        )
        val err = (dto.toDomain() as Result.Failure).error as ConfigError.UnknownEnumValue
        assertThat(err.field).isEqualTo("damage_matrix[\"PICKAXE/WOOD\"].glass")
        assertThat(err.rawValue).isEqualTo("glass")
        assertThat(err.accepted).contains("wood", "stone", "soil", "other")
    }

    @Test
    fun `unknown material in bindings returns UnknownEnumValue failure`() {
        val dto = validDto().copy(
            materials = mapOf("minecraft:glass" to "glass"),
        )
        val err = (dto.toDomain() as Result.Failure).error as ConfigError.UnknownEnumValue
        assertThat(err.field).isEqualTo("materials[\"minecraft:glass\"]")
        assertThat(err.rawValue).isEqualTo("glass")
    }

    @Test
    fun `damage above MAX_DAMAGE returns OutOfRange failure`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("PICKAXE/WOOD" to mapOf("stone" to 9999)),
        )
        val err = (dto.toDomain() as Result.Failure).error
        assertThat(err).isInstanceOf(ConfigError.OutOfRange::class.java)
        err as ConfigError.OutOfRange
        assertThat(err.field).isEqualTo("damage_matrix[\"PICKAXE/WOOD\"].stone")
        assertThat(err.rawValue).isEqualTo("9999")
        assertThat(err.constraint).contains("0..${BlockDurabilityYamlConfig.MAX_DAMAGE}")
    }

    @Test
    fun `negative damage returns OutOfRange failure`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("PICKAXE/WOOD" to mapOf("stone" to -1)),
        )
        val err = (dto.toDomain() as Result.Failure).error as ConfigError.OutOfRange
        assertThat(err.field).isEqualTo("damage_matrix[\"PICKAXE/WOOD\"].stone")
        assertThat(err.rawValue).isEqualTo("-1")
    }

    @Test
    fun `tool key parsing is case-insensitive on input`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("pickaxe/wood" to mapOf("Stone" to 25)),
        )
        val result = dto.toDomain()
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val rule = (result as Result.Success).value
        assertThat(rule.damageFor(ToolType.WOODEN_PICKAXE, BlockMaterial.STONE)).isEqualTo(25)
    }
}
