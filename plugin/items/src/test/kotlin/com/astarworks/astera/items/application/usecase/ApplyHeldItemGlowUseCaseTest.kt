package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.service.glow.InMemoryGlowProfileRegistry
import com.astarworks.astera.items.application.service.glow.RecordingItemRenderer
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ApplyHeldItemGlowUseCaseTest {

    private val player = PlayerId(UUID.randomUUID())
    private val items = InMemoryItemRegistry()
    private val profiles = InMemoryGlowProfileRegistry()
    private val renderer = RecordingItemRenderer()
    private val uc = ApplyHeldItemGlowUseCase(items, renderer, profiles)

    @Test
    fun `holding a legendary item starts an active glow schedule`() {
        val legendary = itemSpec(id = "flex-blade", rarity = Rarity.LEGENDARY)
        items.register(legendary)

        uc.onItemHeld(player, legendary.id, previousItemId = null)

        assertThat(renderer.activeScheduleCount()).isEqualTo(1)
        val sched = renderer.activeSchedules().single()
        assertThat(sched.player).isEqualTo(player)
        assertThat(sched.itemId).isEqualTo(legendary.id)
        assertThat(sched.profile.active).isTrue()
    }

    @Test
    fun `holding a common item does not register any schedule`() {
        val common = itemSpec(id = "common-stick", rarity = Rarity.COMMON)
        items.register(common)

        uc.onItemHeld(player, common.id, previousItemId = null)

        assertThat(renderer.activeScheduleCount()).isZero()
    }

    @Test
    fun `unknown item cancels any prior glow without throwing`() {
        val ghost = ItemId("missing-id")
        uc.onItemHeld(player, ghost, previousItemId = null)
        assertThat(renderer.activeScheduleCount()).isZero()
    }

    @Test
    fun `swap from legendary to common cancels the legendary glow`() {
        val legendary = itemSpec(id = "flex-blade", rarity = Rarity.LEGENDARY)
        val common = itemSpec(id = "wood-stick", rarity = Rarity.COMMON)
        items.register(legendary)
        items.register(common)

        uc.onItemHeld(player, legendary.id, previousItemId = null)
        assertThat(renderer.activeScheduleCount()).isEqualTo(1)

        uc.onItemHeld(player, common.id, previousItemId = legendary.id)
        assertThat(renderer.activeScheduleCount()).isZero()
    }

    @Test
    fun `swap to nothing cancels the previous glow`() {
        val rare = itemSpec(id = "rare-ring", rarity = Rarity.RARE)
        items.register(rare)

        uc.onItemHeld(player, rare.id, previousItemId = null)
        uc.onItemHeld(player, newItemId = null, previousItemId = rare.id)

        assertThat(renderer.activeScheduleCount()).isZero()
    }
}
