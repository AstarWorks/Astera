package com.astarworks.astera.economy.application.usecase.marketplace

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.Result
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
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import com.astarworks.astera.economy.domain.marketplace.MarketplaceListing
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [CreateListingUseCase] (RTM #12 / ADR-0035). Uses inline
 * fakes for [IMarketplaceRepository] and [IItemRegistry] to keep the test
 * self-contained — full Postgres round-trip lives in
 * [com.astarworks.astera.persistence.repository.PostgresMarketplaceRepositoryIT].
 */
public class CreateListingUseCaseTest {

    private val knownItem = ItemId("example-sword")
    private val unknownItem = ItemId("never-registered")
    private val seller = PlayerId(UUID.randomUUID())

    private val items = InMemoryItemRegistry().apply {
        register(simpleItemSpec(knownItem, "IRON_SWORD"))
    }

    private val marketplace = InMemoryMarketplaceRepository()
    private val eventBus = FakeDomainEventBus()
    private val fixedNow = Instant.parse("2026-05-19T10:00:00Z")
    private val fixedId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val uc = CreateListingUseCase(
        marketplace = marketplace,
        items = items,
        eventBus = eventBus,
        clock = { fixedNow },
        idGen = { fixedId },
    )

    @Test
    public fun `happy path persists listing and returns ListingId`() {
        val result = uc.execute(seller, knownItem, ShopPrice(Currency.GOLD, 500L))
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(ListingId(fixedId))

        val stored = marketplace.findById(ListingId(fixedId))
        assertThat(stored).isNotNull
        assertThat(stored!!.seller).isEqualTo(seller)
        assertThat(stored.itemId).isEqualTo(knownItem)
        assertThat(stored.price).isEqualTo(ShopPrice(Currency.GOLD, 500L))
        assertThat(stored.listedAt).isEqualTo(fixedNow)
        assertThat(stored.status).isEqualTo(ListingStatus.Active)
    }

    @Test
    public fun `unknown item rejects with UnknownItem`() {
        val result = uc.execute(seller, unknownItem, ShopPrice(Currency.GOLD, 500L))
        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isEqualTo(CreateListingError.UnknownItem(unknownItem))
        assertThat(marketplace.findById(ListingId(fixedId))).isNull()
    }

    @Test
    public fun `zero price rejects with InvalidPrice`() {
        // ShopPrice itself allows 0; CreateListing tightens to strictly positive.
        val result = uc.execute(seller, knownItem, ShopPrice(Currency.GOLD, 0L))
        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isEqualTo(CreateListingError.InvalidPrice(0L))
        assertThat(marketplace.findById(ListingId(fixedId))).isNull()
    }
}

/** Minimal in-memory [IItemRegistry] for marketplace unit tests. */
internal class InMemoryItemRegistry : IItemRegistry {
    private val byId = mutableMapOf<ItemId, ItemSpec>()
    fun register(spec: ItemSpec) { byId[spec.id] = spec }
    override fun find(key: ItemId): ItemSpec? = byId[key]
    override fun all(): Collection<ItemSpec> = byId.values.toList()
}

/**
 * Minimal valid [ItemSpec] for tests that only care about registry membership.
 * Mirrors the production `content/items/example-sword.yaml` defaults.
 */
internal fun simpleItemSpec(id: ItemId, material: String): ItemSpec = ItemSpec(
    id = id,
    template = ItemTemplate.Vanilla(material = material),
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

/** Minimal in-memory [com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository] for use-case tests. */
internal class InMemoryMarketplaceRepository :
    com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository {
    private val byId = linkedMapOf<ListingId, MarketplaceListing>()
    override fun findById(id: ListingId): MarketplaceListing? = byId[id]
    override fun findActiveBySeller(seller: PlayerId): List<MarketplaceListing> =
        byId.values.filter { it.seller == seller && it.status is ListingStatus.Active }
    override fun findAllActive(): List<MarketplaceListing> =
        byId.values.filter { it.status is ListingStatus.Active }
    override fun save(listing: MarketplaceListing) { byId[listing.id] = listing }
    override fun delete(id: ListingId) { byId.remove(id) }
}
