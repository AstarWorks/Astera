package com.astarworks.astera.economy.domain.matchshop

import com.astarworks.astera.core.domain.item.ItemId

/** The four tabs of the in-match ore shop, in GUI order. [key] is the YAML spelling. */
public enum class MatchShopTab(public val key: String) {
    WEAPONS("weapons"),
    TOOLS("tools"),
    BLOCKS("blocks"),
    ARMOR("armor"),
    ;

    public companion object {
        public fun fromKey(key: String): MatchShopTab? = entries.firstOrNull { it.key == key }
    }
}

/** One thing the in-match shop sells: [stack] units of [itemId] for [price] (ore item id to count, all of them). */
public data class MatchShopOffer(
    val itemId: ItemId,
    val price: Map<ItemId, Int>,
    val tab: MatchShopTab,
    val stack: Int = 1,
) {
    init {
        require(price.isNotEmpty() && price.values.all { it > 0 }) { "match-shop offer '${itemId.value}' needs a positive price" }
        require(stack in 1..64) { "match-shop offer '${itemId.value}' stack must be 1..64" }
    }
}

/** Everything `content/shops/match-shop.yaml` sells, in authored order. */
public data class MatchShopCatalog(val offers: List<MatchShopOffer>) {
    public fun tab(tab: MatchShopTab): List<MatchShopOffer> = offers.filter { it.tab == tab }

    public companion object {
        public val EMPTY: MatchShopCatalog = MatchShopCatalog(emptyList())
    }
}
