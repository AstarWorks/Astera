package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.InMemoryResourcePoolService
import com.astarworks.astera.core.domain.event.PlayerActiveWeaponChanged
import com.astarworks.astera.core.domain.event.ResourceRegenned
import com.astarworks.astera.core.domain.event.ResourceSpent
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
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
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HudUpdateOnResourcePoolChangedListenerTest {

    private val pid = PlayerId(UUID.randomUUID())
    private val pistol = ItemId("pistol")
    private val staff = ItemId("staff")

    private val hud = FakeHudGateway()
    private val items = ItemContents().also {
        it.register(pistol, item(pistol, ResourceType.Ammo, max = 12))
        it.register(staff, item(staff, ResourceType.Mana, max = 60))
    }
    private val pools = InMemoryResourcePoolService(FakeScheduler(), InMemoryDomainEventBus(), items)
    private val listener = HudUpdateOnResourcePoolChangedListener(hud, pools, items)

    private fun item(id: ItemId, type: ResourceType, max: Int) = ItemSpec(
        id = id,
        template = ItemTemplate.Vanilla("STICK", null),
        displayName = LocalizedText.EMPTY,
        lore = LocalizedLore.EMPTY,
        rarity = Rarity.COMMON,
        levelRequirement = 1,
        attributes = ItemAttributes(),
        equipSlot = EquipSlot.MAINHAND,
        durability = DurabilityPolicy(null, false),
        dropPolicy = DropPolicy(dropOnDeath = true, keepOnDeath = false),
        enchantable = true,
        glint = null,
        skillBindings = emptyList(),
        pdcTags = emptyMap(),
        resourceType = type,
        resourceMaxPool = max,
    )

    private fun shown(): List<HudLayout.BossBarSlot> =
        hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>().map { it.layout as HudLayout.BossBarSlot }

    @Test
    fun `spend of the held resource draws remaining over max`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, pistol)
        pools.spendResource(pid, ResourceType.Ammo, 3)

        listener.onSpent(ResourceSpent(pid, ResourceType.Ammo, 3, remaining = 9, atTick = 1))

        val bar = shown().single()
        val text = bar.component as HudComponent.Text
        assertThat(text.messageKey.value).isEqualTo("astera.hud.resource.ammo.label")
        assertThat(text.placeholders).containsEntry("remaining", "9").containsEntry("max", "12")
        assertThat(bar.progress).isEqualTo(0.75f)
    }

    @Test
    fun `a resource the held item does not use is not drawn`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, pistol)

        listener.onRegenned(ResourceRegenned(pid, ResourceType.Mana, 1, newValue = 50, atTick = 1))

        assertThat(shown()).isEmpty()
    }

    @Test
    fun `regen redraws are throttled`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, staff)

        listener.onRegenned(ResourceRegenned(pid, ResourceType.Mana, 1, newValue = 60, atTick = 10))
        listener.onRegenned(ResourceRegenned(pid, ResourceType.Mana, 1, newValue = 60, atTick = 12))
        listener.onRegenned(ResourceRegenned(pid, ResourceType.Mana, 1, newValue = 60, atTick = 15))

        assertThat(shown()).hasSize(2)
    }

    @Test
    fun `switching weapons clears the other resources and draws the new one`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, staff)

        listener.onActiveWeaponChanged(PlayerActiveWeaponChanged(pid, previous = pistol, current = staff, atTick = 1))

        val cleared = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Cleared>().map { it.region }
        assertThat(cleared).containsExactlyInAnyOrder(HudRegion.BOSS_BAR_SLOT_2, HudRegion.BOSS_BAR_SLOT_3)
        assertThat((shown().single().component as HudComponent.Text).messageKey.value)
            .isEqualTo("astera.hud.resource.mana.label")
    }
}
