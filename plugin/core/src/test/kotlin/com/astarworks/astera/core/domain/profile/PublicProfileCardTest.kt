package com.astarworks.astera.core.domain.profile

import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PublicProfileCardTest {

    private val player = PlayerId(UUID.randomUUID())
    private val now = Instant.parse("2026-05-20T00:00:00Z")

    @Test
    fun `empty card has zero counts`() {
        val c = PublicProfileCard.empty(player, "Alice", now)
        assertThat(c.equipment).isEqualTo(EquipmentSummary.EMPTY)
        assertThat(c.titles).isEqualTo(TitleSummary.EMPTY)
        assertThat(c.cosmetics).isEqualTo(CosmeticSummary.EMPTY)
        assertThat(c.powerIndexTotal).isZero()
        assertThat(c.visibility).isEqualTo(ProfileVisibility.PUBLIC)
    }

    @Test
    fun `blank displayName rejected`() {
        assertThatThrownBy {
            PublicProfileCard.empty(player, "  ", now)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative powerIndexTotal rejected`() {
        assertThatThrownBy {
            PublicProfileCard(
                playerId = player,
                displayName = "Alice",
                visibility = ProfileVisibility.PUBLIC,
                equipment = EquipmentSummary.EMPTY,
                titles = TitleSummary.EMPTY,
                cosmetics = CosmeticSummary.EMPTY,
                powerIndexTotal = -1L,
                computedAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative counts rejected per summary`() {
        assertThatThrownBy { EquipmentSummary(-1, null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TitleSummary(-1, null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CosmeticSummary(-1, null) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `visibility defaults to PUBLIC in empty factory`() {
        val c = PublicProfileCard.empty(player, "Alice", now)
        assertThat(c.visibility).isEqualTo(ProfileVisibility.PUBLIC)
    }

    @Test
    fun `visibility can be set to PRIVATE`() {
        val c = PublicProfileCard.empty(player, "Alice", now, visibility = ProfileVisibility.PRIVATE)
        assertThat(c.visibility).isEqualTo(ProfileVisibility.PRIVATE)
    }
}
