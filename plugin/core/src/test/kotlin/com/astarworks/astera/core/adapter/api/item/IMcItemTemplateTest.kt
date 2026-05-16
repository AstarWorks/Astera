package com.astarworks.astera.core.adapter.api.item

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IMcItemTemplateTest {

    private val sampleId = ItemId("example-sword")

    @Test
    fun `vanilla template happy path`() {
        val tpl = IMcItemTemplate.Vanilla(
            id = sampleId,
            material = "IRON_SWORD",
            customModelData = 1001,
            displayName = MessageKey("astera.item.example-sword.name"),
            lore = listOf(MessageKey("astera.item.example-sword.lore")),
            pdcTags = mapOf("astera:rarity" to "common"),
        )
        assertThat(tpl.id).isEqualTo(sampleId)
        assertThat(tpl.material).isEqualTo("IRON_SWORD")
        assertThat(tpl.customModelData).isEqualTo(1001)
        assertThat(tpl.lore).hasSize(1)
    }

    @Test
    fun `vanilla template rejects blank material`() {
        assertThatThrownBy {
            IMcItemTemplate.Vanilla(id = sampleId, material = "")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `vanilla template rejects negative custom model data`() {
        assertThatThrownBy {
            IMcItemTemplate.Vanilla(id = sampleId, material = "IRON_SWORD", customModelData = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `external template happy path`() {
        val tpl = IMcItemTemplate.External(id = sampleId, key = "oraxen:my_blade")
        assertThat(tpl.key).isEqualTo("oraxen:my_blade")
    }

    @Test
    fun `external template rejects blank key`() {
        assertThatThrownBy {
            IMcItemTemplate.External(id = sampleId, key = "")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `sealed exhaustiveness compiles`() {
        val tpl: IMcItemTemplate = IMcItemTemplate.Vanilla(id = sampleId, material = "STONE")
        val materialOrKey: String = when (tpl) {
            is IMcItemTemplate.Vanilla -> tpl.material
            is IMcItemTemplate.External -> tpl.key
        }
        assertThat(materialOrKey).isEqualTo("STONE")
    }
}
