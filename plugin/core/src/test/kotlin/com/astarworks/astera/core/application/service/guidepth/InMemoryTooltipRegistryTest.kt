package com.astarworks.astera.core.application.service.guidepth

import com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipExtensionProvider
import com.astarworks.astera.core.domain.guidepth.ItemCategory
import com.astarworks.astera.core.domain.guidepth.TooltipLine
import com.astarworks.astera.core.domain.guidepth.TooltipScope
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryTooltipRegistryTest {

    private val viewer = PlayerId(UUID.randomUUID())
    private val swordA = ItemId("iron-sword")
    private val swordB = ItemId("mythril-sword")

    private fun line(key: String): TooltipLine =
        TooltipLine.Text(MessageKey(key), HudColor.WHITE)

    private fun provider(vararg keys: String): ITooltipExtensionProvider =
        ITooltipExtensionProvider { _, _ -> keys.map { line(it) } }

    @Test
    fun `AllItems provider fires for every item`() {
        val r = InMemoryTooltipRegistry()
        r.register(TooltipScope.AllItems, provider("all-1"))
        val a = r.lookup(swordA, viewer).map { (it as TooltipLine.Text).messageKey.value }
        val b = r.lookup(swordB, viewer).map { (it as TooltipLine.Text).messageKey.value }
        assertThat(a).containsExactly("all-1")
        assertThat(b).containsExactly("all-1")
    }

    @Test
    fun `ByItem provider only fires for matching item`() {
        val r = InMemoryTooltipRegistry()
        r.register(TooltipScope.ByItem(swordA), provider("only-a"))
        assertThat(r.lookup(swordA, viewer)).hasSize(1)
        assertThat(r.lookup(swordB, viewer)).isEmpty()
    }

    @Test
    fun `ByRarity provider needs rarity resolver to fire`() {
        val r = InMemoryTooltipRegistry(rarityResolver = { item ->
            if (item == swordA) Rarity.LEGENDARY else null
        })
        r.register(TooltipScope.ByRarity(Rarity.LEGENDARY), provider("rare-line"))
        assertThat(r.lookup(swordA, viewer)).hasSize(1)
        assertThat(r.lookup(swordB, viewer)).isEmpty()
    }

    @Test
    fun `ByRarity never fires under default null resolver`() {
        val r = InMemoryTooltipRegistry()
        r.register(TooltipScope.ByRarity(Rarity.LEGENDARY), provider("never"))
        assertThat(r.lookup(swordA, viewer)).isEmpty()
    }

    @Test
    fun `ByCategory provider needs categories resolver to fire`() {
        val weaponCat = ItemCategory("pvp-weapon")
        val r = InMemoryTooltipRegistry(categoriesResolver = { item ->
            if (item == swordA) listOf(weaponCat) else emptyList()
        })
        r.register(TooltipScope.ByCategory(weaponCat), provider("cat-line"))
        assertThat(r.lookup(swordA, viewer)).hasSize(1)
        assertThat(r.lookup(swordB, viewer)).isEmpty()
    }

    @Test
    fun `lookup preserves registration order across scope buckets`() {
        val r = InMemoryTooltipRegistry(rarityResolver = { _ -> Rarity.LEGENDARY })
        // Registration order: all → byRarity → byItem → byItem (second)
        r.register(TooltipScope.AllItems, provider("a"))
        r.register(TooltipScope.ByRarity(Rarity.LEGENDARY), provider("r"))
        r.register(TooltipScope.ByItem(swordA), provider("i1"))
        r.register(TooltipScope.ByItem(swordA), provider("i2"))
        val keys = r.lookup(swordA, viewer).map { (it as TooltipLine.Text).messageKey.value }
        assertThat(keys).containsExactly("a", "r", "i1", "i2")
    }

    @Test
    fun `provider receives viewer parameter`() {
        val r = InMemoryTooltipRegistry()
        val captured = mutableListOf<PlayerId>()
        r.register(TooltipScope.AllItems) { _, v ->
            captured.add(v)
            emptyList()
        }
        r.lookup(swordA, viewer)
        assertThat(captured).containsExactly(viewer)
    }

    @Test
    fun `rarityOf and categoriesOf expose resolver outputs`() {
        val cat = ItemCategory("survival-tool")
        val r = InMemoryTooltipRegistry(
            rarityResolver = { Rarity.RARE },
            categoriesResolver = { listOf(cat) },
        )
        assertThat(r.rarityOf(swordA)).isEqualTo(Rarity.RARE)
        assertThat(r.categoriesOf(swordA)).containsExactly(cat)
    }
}
