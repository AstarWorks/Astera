package com.astarworks.astera.items.application.service.glow

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.domain.glow.GlowProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordingItemRendererTest {

    private val playerA = PlayerId(UUID.randomUUID())
    private val itemSword = ItemId("flex-sword")
    private val itemBow = ItemId("flex-bow")

    private val active = GlowProfile.DEFAULTS.getValue(Rarity.LEGENDARY)
    private val silent = GlowProfile.DEFAULTS.getValue(Rarity.COMMON)

    @Test
    fun `startGlow with active profile records the schedule`() {
        val r = RecordingItemRenderer()
        r.startGlow(playerA, itemSword, active)
        assertThat(r.activeScheduleCount()).isEqualTo(1)
        assertThat(r.activeSchedules()).hasSize(1)
        assertThat(r.activeSchedules().single().profile).isEqualTo(active)
    }

    @Test
    fun `startGlow with inactive profile is a no-op cancel`() {
        val r = RecordingItemRenderer()
        r.startGlow(playerA, itemSword, active)
        r.startGlow(playerA, itemSword, silent)
        assertThat(r.activeScheduleCount()).isZero()
    }

    @Test
    fun `stopGlow cancels the schedule`() {
        val r = RecordingItemRenderer()
        r.startGlow(playerA, itemSword, active)
        r.stopGlow(playerA, itemSword)
        assertThat(r.activeScheduleCount()).isZero()
    }

    @Test
    fun `multiple items per player are tracked independently`() {
        val r = RecordingItemRenderer()
        r.startGlow(playerA, itemSword, active)
        r.startGlow(playerA, itemBow, active)
        assertThat(r.activeScheduleCount()).isEqualTo(2)
        r.stopGlow(playerA, itemSword)
        assertThat(r.activeScheduleCount()).isEqualTo(1)
    }

    @Test
    fun `stopAll clears every schedule`() {
        val r = RecordingItemRenderer()
        r.startGlow(playerA, itemSword, active)
        r.startGlow(playerA, itemBow, active)
        r.stopAll()
        assertThat(r.activeScheduleCount()).isZero()
    }

    @Test
    fun `restart with active profile replaces the previous schedule`() {
        val r = RecordingItemRenderer()
        r.startGlow(playerA, itemSword, active)
        val stronger = active.copy(particleCount = 9)
        r.startGlow(playerA, itemSword, stronger)
        assertThat(r.activeScheduleCount()).isEqualTo(1)
        assertThat(r.activeSchedules().single().profile.particleCount).isEqualTo(9)
    }
}
