package com.astarworks.astera.economy.application.config

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.economy.domain.matchshop.MatchShopCatalog
import com.astarworks.astera.economy.domain.matchshop.MatchShopOffer
import com.astarworks.astera.economy.domain.matchshop.MatchShopTab
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML shape of `content/shops/match-shop.yaml`, the ore shop sold inside a running match:
 *
 * ```yaml
 * offers:
 *   - { item_id: dagger, price: { ore-iron: 8 }, tab: weapons, stack: 1 }
 * ```
 *
 * `tab` is `weapons | tools | blocks | armor`; `price` maps ore item ids to counts (all must be paid); `stack` defaults to 1.
 */
@Serializable
public data class MatchShopYamlConfig(
    val id: String = "match-shop",
    val offers: List<MatchShopOfferYamlConfig> = emptyList(),
) {
    public fun toCatalog(): MatchShopCatalog = MatchShopCatalog(offers.map { it.toDomain() })
}

@Serializable
public data class MatchShopOfferYamlConfig(
    @SerialName("item_id") val itemId: String,
    val price: Map<String, Int>,
    val tab: String,
    val stack: Int = 1,
) {
    public fun toDomain(): MatchShopOffer = MatchShopOffer(
        itemId = ItemId(itemId),
        price = price.mapKeys { ItemId(it.key) },
        tab = requireNotNull(MatchShopTab.fromKey(tab)) { "match-shop offer '$itemId': unknown tab '$tab'" },
        stack = stack,
    )
}
