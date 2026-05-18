package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the `Result<BlockDurabilityRule, ConfigError>` contract for
 * [BlockDurabilityYamlConfig.toDomain]. Authors must see typed failures, not
 * raw JVM throwables (per `coding-posture.md` §"Application API surface").
 */
internal class BlockDurabilityYamlConfigTest {

    private fun validDto() = BlockDurabilityYamlConfig(
        materials = mapOf(
            "minecraft:stone" to "stone",
            "minecraft:oak_log" to "wood",
        ),
        damageMatrix = mapOf(
            "wood" to mapOf("stone" to 25, "soil" to 100),
            "iron" to mapOf("stone" to 100),
        ),
    )

    @Test
    fun `valid DTO converts to BlockDurabilityRule with parsed cells and bindings`() {
        val result = validDto().toDomain()
        assertThat(result).isInstanceOf(Result.Success::class.java)

        val rule = (result as Result.Success).value
        assertThat(rule.damageFor(ToolType.WOOD, BlockMaterial.STONE)).isEqualTo(25)
        assertThat(rule.damageFor(ToolType.WOOD, BlockMaterial.SOIL)).isEqualTo(100)
        assertThat(rule.damageFor(ToolType.IRON, BlockMaterial.STONE)).isEqualTo(100)
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
        assertThat(rule.damageFor(ToolType.WOOD, BlockMaterial.SOIL))
            .isEqualTo(com.astarworks.astera.core.domain.world.BlockDurabilityRule.DEFAULT_DAMAGE)
    }

    @Test
    fun `unknown tool name returns UnknownEnumValue failure`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("platinum" to mapOf("stone" to 50)),
        )
        val err = (dto.toDomain() as Result.Failure).error
        assertThat(err).isInstanceOf(ConfigError.UnknownEnumValue::class.java)
        err as ConfigError.UnknownEnumValue
        assertThat(err.field).isEqualTo("damage_matrix.platinum")
        assertThat(err.rawValue).isEqualTo("platinum")
        assertThat(err.accepted).contains("wood", "stone", "iron", "diamond", "none")
    }

    @Test
    fun `unknown material name in matrix returns UnknownEnumValue failure`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("wood" to mapOf("glass" to 25)),
        )
        val err = (dto.toDomain() as Result.Failure).error as ConfigError.UnknownEnumValue
        assertThat(err.field).isEqualTo("damage_matrix.wood.glass")
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
            damageMatrix = mapOf("wood" to mapOf("stone" to 9999)),
        )
        val err = (dto.toDomain() as Result.Failure).error
        assertThat(err).isInstanceOf(ConfigError.OutOfRange::class.java)
        err as ConfigError.OutOfRange
        assertThat(err.field).isEqualTo("damage_matrix.wood.stone")
        assertThat(err.rawValue).isEqualTo("9999")
        assertThat(err.constraint).contains("0..${BlockDurabilityYamlConfig.MAX_DAMAGE}")
    }

    @Test
    fun `negative damage returns OutOfRange failure`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("wood" to mapOf("stone" to -1)),
        )
        val err = (dto.toDomain() as Result.Failure).error as ConfigError.OutOfRange
        assertThat(err.field).isEqualTo("damage_matrix.wood.stone")
        assertThat(err.rawValue).isEqualTo("-1")
    }

    @Test
    fun `enum parsing is case-insensitive on input`() {
        val dto = validDto().copy(
            damageMatrix = mapOf("WOOD" to mapOf("Stone" to 25)),
        )
        val result = dto.toDomain()
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val rule = (result as Result.Success).value
        assertThat(rule.damageFor(ToolType.WOOD, BlockMaterial.STONE)).isEqualTo(25)
    }
}
