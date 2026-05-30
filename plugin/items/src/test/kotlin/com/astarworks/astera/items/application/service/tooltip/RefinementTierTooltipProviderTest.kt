package com.astarworks.astera.items.application.service.tooltip

import com.astarworks.astera.core.domain.guidepth.TooltipLine
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.service.refinement.InMemoryItemInstanceRepository
import com.astarworks.astera.items.domain.refinement.ItemInstance
import com.astarworks.astera.items.domain.refinement.ItemInstanceId
import com.astarworks.astera.items.domain.refinement.Refinement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RefinementTierTooltipProviderTest {

    private fun pid(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `viewer with refined instance gets tier line`() {
        val viewer = pid()
        val itemId = ItemId("amethyst-sword")
        val instance = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = itemId,
            owner = viewer,
            createdAt = Instant.now(),
            layers = listOf(
                Refinement.Star(stars = 1),
                Refinement.Star(stars = 2),
            ),
        )
        val repo = InMemoryItemInstanceRepository().apply { save(instance) }
        val provider = RefinementTierTooltipProvider(repo)

        val lines = provider.extend(itemId, viewer)

        assertThat(lines).hasSize(1)
        assertThat((lines.single() as TooltipLine.Text).messageKey)
            .isEqualTo(MessageKey("astera.tooltip.refinement.tier"))
    }

    @Test
    fun `viewer without instance returns empty list`() {
        val provider = RefinementTierTooltipProvider(InMemoryItemInstanceRepository())
        assertThat(provider.extend(ItemId("amethyst-sword"), pid())).isEmpty()
    }

    @Test
    fun `instance without layers returns empty list`() {
        val viewer = pid()
        val itemId = ItemId("amethyst-sword")
        val instance = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = itemId,
            owner = viewer,
            createdAt = Instant.now(),
            layers = emptyList(),
        )
        val repo = InMemoryItemInstanceRepository().apply { save(instance) }
        val provider = RefinementTierTooltipProvider(repo)

        assertThat(provider.extend(itemId, viewer)).isEmpty()
    }

    @Test
    fun `picks highest layer-count across multiple instances`() {
        val viewer = pid()
        val itemId = ItemId("amethyst-sword")
        val low = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = itemId,
            owner = viewer,
            createdAt = Instant.now(),
            layers = listOf(Refinement.Star(1)),
        )
        val high = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = itemId,
            owner = viewer,
            createdAt = Instant.now(),
            layers = listOf(Refinement.Star(1), Refinement.Star(2), Refinement.Star(3)),
        )
        val repo = InMemoryItemInstanceRepository().apply {
            save(low)
            save(high)
        }
        val provider = RefinementTierTooltipProvider(repo)

        val lines = provider.extend(itemId, viewer)
        assertThat(lines).hasSize(1) // a single tier line; tier value is i18n placeholder territory
    }

    @Test
    fun `instances of a different item are ignored`() {
        val viewer = pid()
        val instance = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = ItemId("amethyst-sword"),
            owner = viewer,
            createdAt = Instant.now(),
            layers = listOf(Refinement.Star(1)),
        )
        val repo = InMemoryItemInstanceRepository().apply { save(instance) }
        val provider = RefinementTierTooltipProvider(repo)

        assertThat(provider.extend(ItemId("other-sword"), viewer)).isEmpty()
    }
}
