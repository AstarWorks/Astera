package com.astarworks.astera.core.application.config.block

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pins the [CustomBlockYamlConfig.toSpec] contract (Wave 5x, ADR-0090).
 *
 * Authors must see typed `IllegalArgumentException` failures at load time
 * for unknown enums / malformed keys; ContentLoader catches and counts as
 * a failed load (one bad YAML doesn't kill the bootstrap).
 */
internal class CustomBlockYamlConfigTest {

    private fun valid() = CustomBlockYamlConfig(
        id = "astera:hardened_soil",
        displayName = mapOf("en" to "Hardened Soil", "ja" to "硬化土"),
        baseBukkitMaterial = "DIRT",
        material = "SOIL",
        baseDurability = 5000,
        toolEffectiveness = mapOf(
            "PICKAXE/IRON" to 50,
            "PICKAXE/DIAMOND" to 200,
        ),
        drops = listOf(
            BlockDropYamlConfig(
                itemId = "hardened-soil-block",
                countRange = "1..1",
                dropChance = 1.0f,
                requiresTool = "PICKAXE/IRON",
            ),
        ),
        placedFrom = "hardened-soil-block",
    )

    @Test
    fun `valid DTO converts to spec with parsed fields`() {
        val spec = valid().toSpec()
        assertThat(spec.id).isEqualTo(BlockType("astera:hardened_soil"))
        assertThat(spec.displayName.byLocale[LocaleCode.EN]).isEqualTo("Hardened Soil")
        assertThat(spec.displayName.byLocale[LocaleCode.JA]).isEqualTo("硬化土")
        assertThat(spec.baseBukkitMaterial).isEqualTo("DIRT")
        assertThat(spec.material).isEqualTo(BlockMaterial.SOIL)
        assertThat(spec.baseDurability).isEqualTo(5000)
        assertThat(spec.toolEffectiveness).containsEntry(ToolType.IRON_PICKAXE, 50)
        assertThat(spec.toolEffectiveness).containsEntry(ToolType.DIAMOND_PICKAXE, 200)
        assertThat(spec.drops).hasSize(1)
        assertThat(spec.drops[0].itemId).isEqualTo(ItemId("hardened-soil-block"))
        assertThat(spec.drops[0].requiresTool).isEqualTo(ToolType.IRON_PICKAXE)
        assertThat(spec.placedFrom).isEqualTo(ItemId("hardened-soil-block"))
    }

    @Test
    fun `null toolEffectiveness when empty`() {
        val spec = valid().copy(toolEffectiveness = emptyMap()).toSpec()
        assertThat(spec.toolEffectiveness).isNull()
    }

    @Test
    fun `invalid tool key throws IllegalArgumentException`() {
        assertThatThrownBy {
            valid().copy(toolEffectiveness = mapOf("HAMMER/IRON" to 50)).toSpec()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tool_effectiveness")
            .hasMessageContaining("HAMMER/IRON")
    }

    @Test
    fun `tool cell out of range throws`() {
        assertThatThrownBy {
            valid().copy(toolEffectiveness = mapOf("PICKAXE/IRON" to 9999)).toSpec()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("0..")
    }

    @Test
    fun `unknown material throws`() {
        assertThatThrownBy {
            valid().copy(material = "GLASS").toSpec()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown BlockMaterial")
    }

    @Test
    fun `single integer count_range parses as N_N`() {
        val dto = valid().copy(
            drops = listOf(
                BlockDropYamlConfig(itemId = "x", countRange = "3"),
            ),
        )
        val spec = dto.toSpec()
        assertThat(spec.drops[0].countRange).isEqualTo(3..3)
    }

    @Test
    fun `min greater than max count_range throws`() {
        val dto = valid().copy(
            drops = listOf(
                BlockDropYamlConfig(itemId = "x", countRange = "5..1"),
            ),
        )
        assertThatThrownBy { dto.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be <= max")
    }

    @Test
    fun `invalid requires_tool string throws`() {
        val dto = valid().copy(
            drops = listOf(
                BlockDropYamlConfig(itemId = "x", requiresTool = "HAMMER/PLATINUM"),
            ),
        )
        assertThatThrownBy { dto.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires_tool")
    }

    @Test
    fun `immune_to list parses`() {
        val spec = valid().copy(immuneTo = listOf("WOOD", "OTHER")).toSpec()
        assertThat(spec.immuneTo).containsExactlyInAnyOrder(BlockMaterial.WOOD, BlockMaterial.OTHER)
    }

    @Test
    fun `null on_right_click stays null on the spec (backward-compatible default)`() {
        // Wave 5block-embed (ADR-0120): existing YAML without the
        // new field continues to load — defaults flow through.
        val spec = valid().toSpec()
        assertThat(spec.onRightClick).isNull()
    }

    @Test
    fun `on_right_click DTO converts to GuiIntent on the spec`() {
        val dto = valid().copy(
            onRightClick = OnRightClickYamlConfig(
                key = "shop.open",
                payload = mapOf("shop_id" to "lobby-main"),
            ),
        )
        val spec = dto.toSpec()
        assertThat(spec.onRightClick).isNotNull
        assertThat(spec.onRightClick?.key).isEqualTo("shop.open")
        assertThat(spec.onRightClick?.payload).containsEntry("shop_id", "lobby-main")
    }

    @Test
    fun `invalid on_right_click key throws with the block id in the message`() {
        val dto = valid().copy(
            onRightClick = OnRightClickYamlConfig(key = "UPPER.bad"),
        )
        assertThatThrownBy { dto.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("on_right_click")
    }
}
