package com.astarworks.astera.core.application.config.mob.pve

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LootTableYamlConfigTest {

    @Test
    fun `minimal config produces empty table`() {
        val cfg = LootTableYamlConfig(id = "astera:no-drops")
        val (tableId, drops) = cfg.toEntry()
        assertThat(tableId.value).isEqualTo("astera:no-drops")
        assertThat(drops).isEmpty()
    }

    @Test
    fun `multi-row table preserves chance and count`() {
        val cfg = LootTableYamlConfig(
            id = "astera:test-loot",
            drops = listOf(
                LootDropYaml(itemId = "gold", chance = 1.0, minCount = 5, maxCount = 10),
                LootDropYaml(itemId = "gem", chance = 0.01),
                LootDropYaml(itemId = "scroll", chance = 0.0005),
            ),
        )
        val (_, drops) = cfg.toEntry()
        assertThat(drops).hasSize(3)
        assertThat(drops[0].itemId.value).isEqualTo("gold")
        assertThat(drops[0].chance).isEqualTo(1.0)
        assertThat(drops[0].minCount).isEqualTo(5)
        assertThat(drops[0].maxCount).isEqualTo(10)
        assertThat(drops[1].chance).isEqualTo(0.01)
        assertThat(drops[2].chance).isEqualTo(0.0005)
    }

    @Test
    fun `duplicate item ids are rejected`() {
        val cfg = LootTableYamlConfig(
            id = "astera:dup-loot",
            drops = listOf(
                LootDropYaml(itemId = "gold", chance = 1.0),
                LootDropYaml(itemId = "gold", chance = 0.5),
            ),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate item_id")
    }

    @Test
    fun `blank item id rejected`() {
        val cfg = LootTableYamlConfig(
            id = "astera:blank-loot",
            drops = listOf(LootDropYaml(itemId = "", chance = 1.0)),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `chance above 1 rejected by LootDrop validation`() {
        val cfg = LootTableYamlConfig(
            id = "astera:bad-loot",
            drops = listOf(LootDropYaml(itemId = "gold", chance = 1.5)),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("chance must be in [0.0, 1.0]")
    }
}
