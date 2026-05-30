package com.astarworks.astera.items.application.service.tooltip

import com.astarworks.astera.core.domain.guidepth.TooltipLine
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RarityGlowTooltipProviderTest {

    private fun pid(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `common rarity emits white tooltip line`() {
        val registry = InMemoryItemRegistry().apply {
            register(itemSpec(id = "wooden-sword", rarity = Rarity.COMMON))
        }
        val provider = RarityGlowTooltipProvider(registry)

        val lines = provider.extend(ItemId("wooden-sword"), pid())

        assertThat(lines).hasSize(1)
        val line = lines.single() as TooltipLine.Text
        assertThat(line.messageKey).isEqualTo(MessageKey("astera.tooltip.rarity.common"))
        assertThat(line.color).isEqualTo(HudColor.WHITE)
    }

    @Test
    fun `legendary rarity emits gold tooltip line`() {
        val registry = InMemoryItemRegistry().apply {
            register(itemSpec(id = "godslayer", rarity = Rarity.LEGENDARY))
        }
        val provider = RarityGlowTooltipProvider(registry)

        val line = provider.extend(ItemId("godslayer"), pid()).single() as TooltipLine.Text
        assertThat(line.messageKey).isEqualTo(MessageKey("astera.tooltip.rarity.legendary"))
        assertThat(line.color).isEqualTo(HudColor.GOLD)
    }

    @Test
    fun `unknown item returns empty list`() {
        val registry = InMemoryItemRegistry()
        val provider = RarityGlowTooltipProvider(registry)

        assertThat(provider.extend(ItemId("does-not-exist"), pid())).isEmpty()
    }

    @Test
    fun `every rarity has a message key mapping`() {
        assertThat(RarityGlowTooltipProvider.MSG_KEY_BY_RARITY.keys)
            .containsExactlyInAnyOrder(*Rarity.entries.toTypedArray())
    }

    @Test
    fun `every rarity has a color mapping`() {
        assertThat(RarityGlowTooltipProvider.COLOR_BY_RARITY.keys)
            .containsExactlyInAnyOrder(*Rarity.entries.toTypedArray())
    }
}
