package com.astarworks.astera.economy.application.service.validation

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOffer
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.core.domain.shop.ShopSpec
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.core.domain.validation.ContentValidationError
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ShopReferencesItemCheckTest {

    @Test
    fun `passes when every offer item is registered`() {
        val items = LocalItemRegistry().apply { add(itemSpec(ItemId("sword"))) }
        val shops = InMemoryShopRegistry().apply {
            register(
                ShopSpec(
                    id = ShopId("starter"),
                    displayKey = MessageKey("shop.starter.title"),
                    offers = listOf(
                        ShopOffer(
                            id = ShopOfferId("o1"),
                            itemId = ItemId("sword"),
                            price = ShopPrice(Currency.GOLD, 5L),
                            stock = ShopStock.Unlimited,
                        ),
                    ),
                ),
            )
        }
        assertThat(ShopReferencesItemCheck(shops, items).check()).isEmpty()
    }

    @Test
    fun `reports MissingReference for unknown item id with correct field path`() {
        val items = LocalItemRegistry() // empty
        val shops = InMemoryShopRegistry().apply {
            register(
                ShopSpec(
                    id = ShopId("s1"),
                    displayKey = MessageKey("shop.s1.title"),
                    offers = listOf(
                        ShopOffer(
                            id = ShopOfferId("a"),
                            itemId = ItemId("ghost"),
                            price = ShopPrice(Currency.GOLD, 1L),
                            stock = ShopStock.Unlimited,
                        ),
                        ShopOffer(
                            id = ShopOfferId("b"),
                            itemId = ItemId("phantom"),
                            price = ShopPrice(Currency.GOLD, 1L),
                            stock = ShopStock.Unlimited,
                        ),
                    ),
                ),
            )
        }
        val errors = ShopReferencesItemCheck(shops, items).check()
        assertThat(errors).hasSize(2)
        assertThat(errors.filterIsInstance<ContentValidationError.MissingReference>())
            .extracting<String> { it.field }
            .containsExactlyInAnyOrder("offers[0].itemId", "offers[1].itemId")
    }

    @Test
    fun `walks every registered shop`() {
        val items = LocalItemRegistry()
        val shops = InMemoryShopRegistry().apply {
            repeat(3) { i ->
                register(
                    ShopSpec(
                        id = ShopId("s$i"),
                        displayKey = MessageKey("shop.s$i.title"),
                        offers = listOf(
                            ShopOffer(
                                id = ShopOfferId("only"),
                                itemId = ItemId("missing$i"),
                                price = ShopPrice(Currency.GOLD, 1L),
                                stock = ShopStock.Unlimited,
                            ),
                        ),
                    ),
                )
            }
        }
        assertThat(ShopReferencesItemCheck(shops, items).check()).hasSize(3)
    }

    private class LocalItemRegistry : IItemRegistry {
        private val store = mutableMapOf<ItemId, ItemSpec>()
        fun add(spec: ItemSpec) { store[spec.id] = spec }
        override fun find(key: ItemId): ItemSpec? = store[key]
        override fun all(): Collection<ItemSpec> = store.values.toList()
    }

    private fun itemSpec(id: ItemId): ItemSpec = ItemSpec(
        id = id,
        template = ItemTemplate.Vanilla(material = "IRON_SWORD"),
        displayName = LocalizedText.EMPTY,
        lore = LocalizedLore.EMPTY,
        rarity = Rarity.COMMON,
        levelRequirement = 1,
        attributes = ItemAttributes.EMPTY,
        equipSlot = EquipSlot.MAINHAND,
        durability = DurabilityPolicy.DEFAULT,
        dropPolicy = DropPolicy.DEFAULT,
        enchantable = true,
        glint = false,
        skillBindings = emptyList(),
        pdcTags = emptyMap(),
    )
}
