package com.astarworks.astera.core.application.profile

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.profile.CosmeticSummary
import com.astarworks.astera.core.domain.profile.EquipmentSummary
import com.astarworks.astera.core.domain.profile.ProfileVisibility
import com.astarworks.astera.core.domain.profile.TitleSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RecomputeProfileCardUseCaseTest {

    private val player = PlayerId(UUID.randomUUID())
    private val now = Instant.parse("2026-05-20T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `recompute populates every summary from providers`() {
        val cards = InMemoryProfileCardService()
        val uc = RecomputeProfileCardUseCase(
            cards = cards,
            displayNameProvider = { "Alice" },
            visibilityProvider = { ProfileVisibility.PUBLIC },
            equipmentProvider = { EquipmentSummary(itemCount = 12, featuredItemId = "flex-sword") },
            titlesProvider = { TitleSummary(unlockedCount = 5, equippedTitleId = "champion") },
            cosmeticsProvider = { CosmeticSummary(unlockedCount = 3, featuredCosmeticId = "red-cape") },
            powerIndexTotalProvider = { 9_999L },
            clock = clock,
        )
        val card = uc.recompute(player)
        assertThat(card).isNotNull
        assertThat(cards.find(player)).isEqualTo(card)
        assertThat(card!!.displayName).isEqualTo("Alice")
        assertThat(card.equipment.itemCount).isEqualTo(12)
        assertThat(card.titles.equippedTitleId).isEqualTo("champion")
        assertThat(card.cosmetics.featuredCosmeticId).isEqualTo("red-cape")
        assertThat(card.powerIndexTotal).isEqualTo(9_999L)
        assertThat(card.computedAt).isEqualTo(now)
    }

    @Test
    fun `null displayName aborts recompute and skips save`() {
        val cards = InMemoryProfileCardService()
        val uc = RecomputeProfileCardUseCase(
            cards = cards,
            displayNameProvider = { null },
            clock = clock,
        )
        assertThat(uc.recompute(player)).isNull()
        assertThat(cards.find(player)).isNull()
    }

    @Test
    fun `negative powerIndex from provider is clamped to zero`() {
        val cards = InMemoryProfileCardService()
        val uc = RecomputeProfileCardUseCase(
            cards = cards,
            displayNameProvider = { "Bob" },
            powerIndexTotalProvider = { -500L },
            clock = clock,
        )
        val card = uc.recompute(player)!!
        assertThat(card.powerIndexTotal).isZero()
    }

    @Test
    fun `recompute overwrites prior snapshot`() {
        val cards = InMemoryProfileCardService()
        var pxValue = 100L
        val uc = RecomputeProfileCardUseCase(
            cards = cards,
            displayNameProvider = { "Bob" },
            powerIndexTotalProvider = { pxValue },
            clock = clock,
        )
        uc.recompute(player)
        pxValue = 500L
        uc.recompute(player)
        assertThat(cards.find(player)?.powerIndexTotal).isEqualTo(500L)
    }
}
