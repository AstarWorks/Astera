package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.InMemoryResourcePoolService
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.ResourcePackStatus
import com.astarworks.astera.core.domain.hud.status.AmmoReadoutLayout
import com.astarworks.astera.core.domain.hud.status.HpBarLayout
import com.astarworks.astera.core.domain.hud.status.StatusHudGlyphs
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
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakePlayerHealthGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HpStatusHudServiceTest {

    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())
    private val scheduler = FakeScheduler()
    private val health = FakePlayerHealthGateway()
    private val hud = FakeHudGateway()
    private val items = ItemContents()
    private val pools = InMemoryResourcePoolService(scheduler, InMemoryDomainEventBus(), items)
    private val service = HpStatusHudService(health, hud, pools, items, scheduler)
    private val pistol = ItemId("test-pistol")
    private val sword = ItemId("test-sword")

    private fun item(id: ItemId, type: ResourceType?, max: Int = -1): ItemSpec = ItemSpec(
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
        modelSlug = null,
        resourceType = type,
        resourceCostPerUse = 1,
        resourceMaxPool = max,
        resourceRegenPerSecond = -1.0,
        resourceReloadable = type == ResourceType.Ammo,
    )

    init {
        items.register(pistol, item(pistol, ResourceType.Ammo, max = 12))
        items.register(sword, item(sword, ResourceType.Energy))
    }

    private fun shown(player: PlayerId): List<HudLayout.StatusLine> = hud.operations
        .mapNotNull { (it as? FakeHudGateway.HudOp.Shown)?.takeIf { op -> op.playerId == player }?.layout as? HudLayout.StatusLine } +
        hud.operations.mapNotNull { (it as? FakeHudGateway.HudOp.Updated)?.layout as? HudLayout.StatusLine }

    private fun setHp(player: PlayerId, hp: Double, max: Double = 200.0) {
        health.setMaxHealth(player, max)
        health.setHealth(player, hp, HealthChangeSource.Other("test"))
    }

    private fun lastLine(player: PlayerId): HudLayout.StatusLine {
        val handle = hud.activeFor(player).single()
        return checkNotNull(hud.currentLayout(handle) as? HudLayout.StatusLine)
    }

    @Test
    fun `without a loaded pack the status line is plain text with exact numbers`() {
        setHp(alice, 123.4)
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)

        val line = lastLine(alice)
        val text = line.component as HudComponent.Text
        assertThat(text.messageKey.value).isEqualTo("astera.hud.status.hp")
        assertThat(text.placeholders).containsEntry("current", "124").containsEntry("max", "200")
        assertThat(line.anchorLeftPx).isNull()
    }

    @Test
    fun `once the client reports the pack loaded the line becomes the glyph bar anchored over the hearts`() {
        setHp(alice, 100.0)
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)

        service.onPackStatus(ResourcePackStatusChanged(alice, ResourcePackStatus.LOADED))
        scheduler.advanceTicks(2)

        val line = lastLine(alice)
        assertThat(line.component).isEqualTo(HpBarLayout.render(100.0, 200.0))
        assertThat(line.anchorLeftPx).isEqualTo(HpBarLayout.HEARTS_LEFT_PX)
    }

    @Test
    fun `accepted and declined do not switch to glyphs, and a later decline-after-load switches back`() {
        setHp(alice, 100.0)
        service.onJoined(PlayerJoinedServer(alice))
        service.onPackStatus(ResourcePackStatusChanged(alice, ResourcePackStatus.ACCEPTED))
        scheduler.advanceTicks(2)
        assertThat(lastLine(alice).component).isInstanceOf(HudComponent.Text::class.java)

        service.onPackStatus(ResourcePackStatusChanged(alice, ResourcePackStatus.LOADED))
        scheduler.advanceTicks(2)
        assertThat(lastLine(alice).component).isInstanceOf(HudComponent.FontText::class.java)

        service.onPackStatus(ResourcePackStatusChanged(alice, ResourcePackStatus.FAILED))
        scheduler.advanceTicks(2)
        assertThat(lastLine(alice).component).isInstanceOf(HudComponent.Text::class.java)
    }

    @Test
    fun `damage, heal and max-HP changes update the line, with or without an event`() {
        setHp(alice, 200.0)
        service.onPackStatus(ResourcePackStatusChanged(alice, ResourcePackStatus.LOADED))
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)
        assertThat(lastLine(alice).component).isEqualTo(HpBarLayout.render(200.0, 200.0))

        setHp(alice, 80.0)
        scheduler.advanceTicks(2)
        assertThat(lastLine(alice).component).isEqualTo(HpBarLayout.render(80.0, 200.0))

        setHp(alice, 120.0, max = 300.0)
        scheduler.advanceTicks(2)
        assertThat(lastLine(alice).component).isEqualTo(HpBarLayout.render(120.0, 300.0))
    }

    @Test
    fun `a writer that never raises an event is still picked up by the poll`() {
        setHp(alice, 200.0)
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)
        // The combat damage gateway writes the container directly, with no PlayerHealthChanged.
        setHp(alice, 30.0)
        scheduler.advanceTicks(2)
        assertThat((lastLine(alice).component as HudComponent.Text).placeholders).containsEntry("current", "30")
    }

    @Test
    fun `a burst of changes inside one flush window costs one update`() {
        setHp(alice, 200.0)
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)
        val before = hud.operations.size

        for (hp in listOf(190.0, 170.0, 150.0, 120.0)) {
            setHp(alice, hp)
        }
        scheduler.advanceTicks(1)
        assertThat(hud.operations).hasSize(before) // nothing yet: throttled
        scheduler.advanceTicks(1)

        assertThat(hud.operations).hasSize(before + 1)
        assertThat((lastLine(alice).component as HudComponent.Text).placeholders).containsEntry("current", "120")
    }

    @Test
    fun `an event that leaves the rendered line unchanged sends nothing`() {
        setHp(alice, 100.0)
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)
        val before = hud.operations.size
        scheduler.advanceTicks(4)

        assertThat(hud.operations).hasSize(before)
    }

    @Test
    fun `a player with no HP in the container shows full default health, not nothing`() {
        service.onJoined(PlayerJoinedServer(bob)) // nothing seeds the container on join
        scheduler.advanceTicks(2)
        val text = lastLine(bob).component as HudComponent.Text
        assertThat(text.placeholders).containsEntry("current", "20").containsEntry("max", "20")

        service.stop()
    }

    @Test
    fun `players are independent`() {
        setHp(alice, 50.0)
        service.onJoined(PlayerJoinedServer(alice))
        service.onJoined(PlayerJoinedServer(bob))
        setHp(bob, 10.0, max = 20.0)
        scheduler.advanceTicks(2)
        scheduler.advanceTicks(2)
        assertThat((lastLine(bob).component as HudComponent.Text).placeholders).containsEntry("current", "10").containsEntry("max", "20")
        assertThat((lastLine(alice).component as HudComponent.Text).placeholders).containsEntry("current", "50")
    }

    @Test
    fun `a player who quit is forgotten so a rejoin redraws from scratch`() {
        setHp(alice, 100.0)
        service.onPackStatus(ResourcePackStatusChanged(alice, ResourcePackStatus.LOADED))
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)
        service.onQuit(PlayerQuitServer(alice))

        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)

        // The pack state was forgotten with the session: text form until the client reports again.
        assertThat(lastLine(alice).component).isInstanceOf(HudComponent.Text::class.java)
    }

    @Test
    fun `stop cancels the flush task`() {
        setHp(alice, 100.0)
        service.stop()
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(10)
        assertThat(hud.operations).isEmpty()
        assertThat(shown(alice)).isEmpty()
    }

    @Test
    fun `holding a gun adds the ammo to the text line, holding a sword or nothing removes it`() {
        setHp(alice, 200.0)
        pools.initialiseFor(alice)
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)
        assertThat((lastLine(alice).component as HudComponent.Text).placeholders).doesNotContainKey("ammo")

        pools.setActiveWeapon(alice, pistol)
        pools.spendResource(alice, ResourceType.Ammo, 4)
        scheduler.advanceTicks(2)
        val text = lastLine(alice).component as HudComponent.Text
        assertThat(text.messageKey.value).isEqualTo("astera.hud.status.hp-ammo")
        assertThat(text.placeholders).containsEntry("ammo", "8").containsEntry("ammoMax", "12")

        pools.setActiveWeapon(alice, sword)
        scheduler.advanceTicks(2)
        assertThat((lastLine(alice).component as HudComponent.Text).messageKey.value).isEqualTo("astera.hud.status.hp")
    }

    @Test
    fun `with the pack loaded the ammo is a glyph readout right of the HP bar`() {
        setHp(alice, 100.0)
        pools.initialiseFor(alice)
        pools.setActiveWeapon(alice, pistol)
        pools.spendResource(alice, ResourceType.Ammo, 5)
        service.onPackStatus(ResourcePackStatusChanged(alice, ResourcePackStatus.LOADED))
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(2)

        val line = lastLine(alice)
        val expected = AmmoReadoutLayout.appendTo(HpBarLayout.render(100.0, 200.0), AmmoReadoutLayout.render(7, 12))
        assertThat(line.component).isEqualTo(expected)
        assertThat((line.component as HudComponent.FontText).text).contains(StatusHudGlyphs.AMMO.toString())
        assertThat(line.anchorLeftPx).isEqualTo(HpBarLayout.HEARTS_LEFT_PX)
    }
}
