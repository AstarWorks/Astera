package com.astarworks.astera.economy.application.config

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.shop.PurchaseLimit
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOffer
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.core.domain.shop.ShopSpec
import com.astarworks.astera.core.domain.shop.ShopStock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/shops/` definition files (Wave 4.5 R3.5, ADR-0027).
 *
 * Kept separate from [ShopSpec] so the domain stays free of serialization
 * annotations (Konsist enforces this; see `principles.md` §6 and ADR-0016).
 * The wire format is decided by `ContentLoader` per ADR-0016; the polymorphic
 * discriminator key for [StockYamlConfig] is `type` and matches kaml's
 * `PolymorphismStyle.Property` configuration so the same DTO works across
 * formats (YAML / TOML / JSON).
 *
 * Mirrors the [GuiYamlConfig] / [com.astarworks.astera.stats.application.config.NormaYamlConfig]
 * pattern: top-level data class + sealed polymorphic child + flat `toSpec()`.
 *
 * `currency` (inside [PriceYamlConfig]) accepts the [Currency] enum names
 * (case-insensitive): `star`, `gold`, `gem`.
 */
@Serializable
public data class ShopYamlConfig(
    val id: String,
    @SerialName("display_key") val displayKey: String,
    val offers: List<ShopOfferYamlConfig> = emptyList(),
) {
    /** Materialize this DTO into the domain [ShopSpec]. */
    public fun toSpec(): ShopSpec = ShopSpec(
        id = ShopId(id),
        displayKey = MessageKey(displayKey),
        offers = offers.map { it.toDomain() },
    )
}

/**
 * YAML-shaped DTO for one entry inside [ShopYamlConfig.offers].
 *
 * `item` is the [ItemId] the buyer receives — in Wave 4.5 this MUST be a weapon
 * id (the [com.astarworks.astera.economy.application.usecase.shop.ShopPurchaseUseCase]
 * rejects non-deliverables with `DeliveryFailed`). Schema generation does not
 * yet emit that constraint; content authors rely on the per-offer test catalog.
 */
@Serializable
public data class ShopOfferYamlConfig(
    val id: String,
    val item: String,
    val price: PriceYamlConfig,
    val stock: StockYamlConfig,
    @SerialName("display_key") val displayKey: String? = null,
    /** `once` (default: an unlock, paid once) or `unlimited` (consumable). */
    val limit: String = "once",
    val category: String? = null,
) {
    public fun toDomain(): ShopOffer = ShopOffer(
        id = ShopOfferId(id),
        itemId = ItemId(item),
        price = price.toDomain(),
        stock = stock.toDomain(),
        displayKey = displayKey?.let { MessageKey(it) },
        limit = PurchaseLimit.valueOf(limit.uppercase()),
        category = category,
    )
}

/**
 * YAML-shaped DTO for the `price:` block.
 *
 * `currency` is decoded via `Currency.valueOf(uppercase)` so authors can write
 * `currency: star` idiomatically; the case-insensitive match throws
 * [IllegalArgumentException] on unknown names (caught and logged by
 * [com.astarworks.astera.core.application.service.ContentLoader]).
 */
@Serializable
public data class PriceYamlConfig(
    val currency: String,
    val amount: Long,
) {
    public fun toDomain(): ShopPrice = ShopPrice(
        currency = Currency.valueOf(currency.uppercase()),
        amount = amount,
    )
}

/**
 * Typed stock payload, matching the sealed [ShopStock] hierarchy.
 *
 * Polymorphic by the `type:` discriminator (kotlinx-JSON convention; kaml's
 * `PolymorphismStyle.Property` reads the same property). Mirrors the
 * `NormaTargetYaml` / `GuiSlotYamlConfig` style so the YAML reads naturally to
 * anyone already familiar with the existing content kinds.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class StockYamlConfig {

    public abstract fun toDomain(): ShopStock

    @Serializable
    @SerialName("unlimited")
    public data object Unlimited : StockYamlConfig() {
        override fun toDomain(): ShopStock = ShopStock.Unlimited
    }

    @Serializable
    @SerialName("limited")
    public data class Limited(val remaining: Int) : StockYamlConfig() {
        override fun toDomain(): ShopStock = ShopStock.Limited(remaining)
    }
}
