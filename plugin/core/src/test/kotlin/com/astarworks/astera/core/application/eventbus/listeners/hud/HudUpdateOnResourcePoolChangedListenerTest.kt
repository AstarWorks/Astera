package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.InMemoryResourcePoolService
import com.astarworks.astera.core.domain.event.PlayerActiveWeaponChanged
import com.astarworks.astera.core.domain.event.ResourceRegenned
import com.astarworks.astera.core.domain.event.ResourceReloadStarted
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.event.ResourceSpent
import com.astarworks.astera.core.domain.hud.BossBarColor
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
    private val scheduler = FakeScheduler()
    private val pools = InMemoryResourcePoolService(scheduler, InMemoryDomainEventBus(), items)
    private val listener = HudUpdateOnResourcePoolChangedListener(hud, pools, items, scheduler)

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
        resourceReloadable = type == ResourceType.Ammo,
    )

    /** Every layout the gateway was asked to draw, in order (first draw = Shown, redraws = Updated). */
    private fun drawn(): List<HudLayout.BossBarSlot> = hud.operations.mapNotNull {
        when (it) {
            is FakeHudGateway.HudOp.Shown -> it.layout as? HudLayout.BossBarSlot
            is FakeHudGateway.HudOp.Updated -> it.layout as? HudLayout.BossBarSlot
            else -> null
        }
    }

    private fun label(bar: HudLayout.BossBarSlot): String = (bar.component as HudComponent.Text).messageKey.value

    @Test
    fun `spend of the held resource draws remaining over max`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, pistol)
        pools.spendResource(pid, ResourceType.Ammo, 3)

        listener.onSpent(ResourceSpent(pid, ResourceType.Ammo, 3, remaining = 9, atTick = 1))

        val bar = drawn().single()
        val text = bar.component as HudComponent.Text
        assertThat(text.messageKey.value).isEqualTo("astera.hud.resource.ammo.label")
        assertThat(text.placeholders).containsEntry("remaining", "9").containsEntry("max", "12")
        assertThat(bar.progress).isEqualTo(0.75f)
        assertThat(bar.slot).isEqualTo(HudUpdateOnResourcePoolChangedListener.SLOT)
    }

    @Test
    fun `a resource the held item does not use is not drawn`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, pistol)

        listener.onRegenned(ResourceRegenned(pid, ResourceType.Mana, 1, newValue = 50, atTick = 1))

        assertThat(drawn()).isEmpty()
    }

    @Test
    fun `regen redraws are throttled and unchanged content is not redrawn`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, staff)
        pools.spendResource(pid, ResourceType.Mana, 20)

        listener.onRegenned(ResourceRegenned(pid, ResourceType.Mana, 1, newValue = 41, atTick = 10))
        listener.onRegenned(ResourceRegenned(pid, ResourceType.Mana, 1, newValue = 41, atTick = 12)) // throttled
        listener.onRegenned(ResourceRegenned(pid, ResourceType.Mana, 1, newValue = 41, atTick = 15)) // same content

        assertThat(drawn()).hasSize(1)
    }

    @Test
    fun `empty ammo turns the bar red with a reload prompt and a reload flashes green`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, pistol)
        pools.spendResource(pid, ResourceType.Ammo, 12)
        listener.onSpent(ResourceSpent(pid, ResourceType.Ammo, 12, remaining = 0, atTick = 1))

        val empty = drawn().last()
        assertThat(label(empty)).isEqualTo("astera.hud.resource.ammo.empty")
        assertThat(empty.color).isEqualTo(BossBarColor.RED)

        pools.reload(pid, pistol)
        listener.onReloaded(ResourceReloaded(pid, pistol, ammoAfter = 12, atTick = 2))

        val reloaded = drawn().last()
        assertThat(label(reloaded)).isEqualTo("astera.hud.resource.ammo.reloaded")
        assertThat(reloaded.color).isEqualTo(BossBarColor.GREEN)
        assertThat(reloaded.progress).isEqualTo(1f)
    }

    @Test
    fun `a reload shows a yellow bar that fills with the timer and ends on the green flash`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, pistol)
        pools.spendResource(pid, ResourceType.Ammo, 12)

        listener.onReloadStarted(ResourceReloadStarted(pid, pistol, durationTicks = 30, atTick = 0))
        scheduler.advanceTicks(15)

        val mid = drawn().last()
        assertThat(label(mid)).isEqualTo("astera.hud.resource.ammo.reloading")
        assertThat(mid.color).isEqualTo(BossBarColor.YELLOW)
        assertThat(mid.progress).isEqualTo(0.5f)
        // Redrawn in 5-tick steps (start, 5, 10, 15), still one handle.
        assertThat(drawn()).hasSize(4)
        assertThat(hud.activeFor(pid)).hasSize(1)

        scheduler.advanceTicks(15)
        pools.reload(pid, pistol)
        listener.onReloaded(ResourceReloaded(pid, pistol, ammoAfter = 12, atTick = 30))
        assertThat(label(drawn().last())).isEqualTo("astera.hud.resource.ammo.reloaded")

        val drawnAfterFlash = drawn().size
        scheduler.advanceTicks(20)
        assertThat(drawn()).hasSize(drawnAfterFlash) // the animation stopped with the reload
    }

    @Test
    fun `switching to an item without a resource clears the bar and switching back redraws`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, staff)
        listener.onActiveWeaponChanged(PlayerActiveWeaponChanged(pid, previous = pistol, current = staff, atTick = 1))
        assertThat(label(drawn().single())).isEqualTo("astera.hud.resource.mana.label")

        pools.setActiveWeapon(pid, null)
        listener.onActiveWeaponChanged(PlayerActiveWeaponChanged(pid, previous = staff, current = null, atTick = 2))

        val cleared = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Cleared>().map { it.region }
        assertThat(cleared).containsExactly(HudRegion.BOSS_BAR_SLOT_1)
        assertThat(hud.activeFor(pid)).isEmpty()
    }

    @Test
    fun `the weapon resource only ever occupies one boss bar handle`() {
        pools.initialiseFor(pid)
        pools.setActiveWeapon(pid, pistol)
        repeat(3) { i ->
            pools.spendResource(pid, ResourceType.Ammo, 1)
            listener.onSpent(ResourceSpent(pid, ResourceType.Ammo, 1, remaining = 11 - i, atTick = i.toLong()))
        }

        assertThat(drawn()).hasSize(3)
        assertThat(hud.activeFor(pid)).hasSize(1)
    }
}
