package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.HudCleared
import com.astarworks.astera.core.domain.event.HudShown
import com.astarworks.astera.core.domain.event.HudUpdated
import com.astarworks.astera.core.domain.event.ResourcePackPushRequested
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudGlyph
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeCustomFontRegistry
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class HudGatewayTest {

    private val alice = PlayerId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val bob = PlayerId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

    // ttlTicks set high (1200 = max, 1min @ 20tps) for non-TTL tests; tests
    // that exercise auto-clear use a separate layout with a smaller ttlTicks.
    // Worker α tightened ActionBar.ttlTicks validation to 1..1200 in Wave 5j.
    private val actionBarLayout: HudLayout = HudLayout.ActionBar(
        component = HudComponent.Text(MessageKey("hud.example")),
        ttlTicks = 1200,
    )

    private val sidebarLayout: HudLayout = HudLayout.Sidebar(
        titleKey = MessageKey("hud.sidebar.title"),
        lines = listOf(HudComponent.Text(MessageKey("hud.sidebar.l1"))),
    )

    private val packSpec = ResourcePackSpec(
        url = "https://example.com/pack.zip",
        sha1Hash = "a".repeat(40),
        required = false,
        promptKey = MessageKey("hud.pack.prompt"),
    )

    private lateinit var bus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var gateway: InMemoryHudGateway

    @BeforeEach
    fun setUp() {
        bus = FakeDomainEventBus()
        scheduler = FakeScheduler()
        gateway = InMemoryHudGateway(eventBus = bus, scheduler = scheduler)
    }

    // -------------------------------------------------------------------------
    // showFor
    // -------------------------------------------------------------------------

    @Test
    fun `showFor returns Success with non-blank handle and tracks in active map`() {
        val result = gateway.showFor(alice, actionBarLayout)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val handle = (result as Result.Success).value
        assertThat(handle.value).isNotBlank()
        assertThat(gateway.activeFor(alice)).containsExactly(handle)
    }

    @Test
    fun `showFor publishes HudShown event via the domain event bus`() {
        val result = gateway.showFor(alice, actionBarLayout) as Result.Success
        val handle = result.value

        val shown = bus.publishedOfType<HudShown>()
        assertThat(shown).hasSize(1)
        assertThat(shown.first().playerId).isEqualTo(alice)
        assertThat(shown.first().handle).isEqualTo(handle)
        assertThat(shown.first().layout).isEqualTo(actionBarLayout)
    }

    // -------------------------------------------------------------------------
    // updateFor
    // -------------------------------------------------------------------------

    @Test
    fun `updateFor on unknown handle returns UnknownHandle`() {
        val phantom = HudHandle("does-not-exist")

        val result = gateway.updateFor(phantom, sidebarLayout)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isEqualTo(HudError.UnknownHandle(phantom))
    }

    @Test
    fun `updateFor on known handle replaces layout and publishes HudUpdated`() {
        val handle = (gateway.showFor(alice, actionBarLayout) as Result.Success).value
        val newLayout = HudLayout.ActionBar(
            component = HudComponent.Text(MessageKey("hud.updated")),
            ttlTicks = 1200,
        )

        val result = gateway.updateFor(handle, newLayout)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val updates = bus.publishedOfType<HudUpdated>()
        assertThat(updates).hasSize(1)
        assertThat(updates.first().handle).isEqualTo(handle)
        assertThat(updates.first().playerId).isEqualTo(alice)
        assertThat(updates.first().layout).isEqualTo(newLayout)
    }

    // -------------------------------------------------------------------------
    // clearFor
    // -------------------------------------------------------------------------

    @Test
    fun `clearFor ACTION_BAR removes the action bar handle of that player`() {
        // A region holds one handle per player (ADR-0177): the second show reuses the first.
        val ab1 = (gateway.showFor(alice, actionBarLayout) as Result.Success).value
        val ab2 = (gateway.showFor(alice, actionBarLayout) as Result.Success).value
        assertThat(ab2).isEqualTo(ab1)
        // unrelated handle that must NOT be cleared
        val side = (gateway.showFor(alice, sidebarLayout) as Result.Success).value

        gateway.clearFor(alice, HudRegion.ACTION_BAR)

        assertThat(gateway.activeFor(alice)).containsExactly(side)
        val cleared = bus.publishedOfType<HudCleared>()
        assertThat(cleared).hasSize(1)
        assertThat(cleared.first().region).isEqualTo(HudRegion.ACTION_BAR)
        assertThat(cleared.first().clearedHandles).containsExactly(ab1)
    }

    @Test
    fun `clearFor on a region with no active HUDs returns Success and publishes empty cleared list`() {
        val result = gateway.clearFor(alice, HudRegion.SIDEBAR)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val cleared = bus.publishedOfType<HudCleared>()
        assertThat(cleared).hasSize(1)
        assertThat(cleared.first().clearedHandles).isEmpty()
    }

    @Test
    fun `clearFor only clears handles for the requested player`() {
        val aliceHandle = (gateway.showFor(alice, actionBarLayout) as Result.Success).value
        val bobHandle = (gateway.showFor(bob, actionBarLayout) as Result.Success).value

        gateway.clearFor(alice, HudRegion.ACTION_BAR)

        assertThat(gateway.activeFor(alice)).isEmpty()
        assertThat(gateway.activeFor(bob)).containsExactly(bobHandle)
        // make sure we didn't accidentally clear Bob's handle
        assertThat(aliceHandle).isNotEqualTo(bobHandle)
    }

    @Test
    fun `clearFor SIDEBAR only clears the sidebar not the action bar`() {
        val bar = (gateway.showFor(alice, actionBarLayout) as Result.Success).value
        val side = (gateway.showFor(alice, sidebarLayout) as Result.Success).value

        gateway.clearFor(alice, HudRegion.SIDEBAR)

        assertThat(gateway.activeFor(alice)).containsExactly(bar)
        val cleared = bus.publishedOfType<HudCleared>().last()
        assertThat(cleared.clearedHandles).containsExactly(side)
    }

    // -------------------------------------------------------------------------
    // pushResourcePack
    // -------------------------------------------------------------------------

    @Test
    fun `pushResourcePack publishes ResourcePackPushRequested event`() {
        val result = gateway.pushResourcePack(alice, packSpec)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val pushed = bus.publishedOfType<ResourcePackPushRequested>()
        assertThat(pushed).hasSize(1)
        assertThat(pushed.first().playerId).isEqualTo(alice)
        assertThat(pushed.first().pack).isEqualTo(packSpec)
    }

    // -------------------------------------------------------------------------
    // activeFor
    // -------------------------------------------------------------------------

    @Test
    fun `activeFor returns only the requested player's handles`() {
        val aliceA = (gateway.showFor(alice, actionBarLayout) as Result.Success).value
        val aliceB = (gateway.showFor(alice, sidebarLayout) as Result.Success).value
        val bobA = (gateway.showFor(bob, actionBarLayout) as Result.Success).value

        assertThat(gateway.activeFor(alice)).containsExactlyInAnyOrder(aliceA, aliceB)
        assertThat(gateway.activeFor(bob)).containsExactly(bobA)
    }

    // -------------------------------------------------------------------------
    // TTL auto-clear (scheduler-driven)
    // -------------------------------------------------------------------------

    @Test
    fun `ActionBar with ttlTicks auto-clears after the scheduled tick count`() {
        val ttlLayout = HudLayout.ActionBar(
            component = HudComponent.Text(MessageKey("hud.flash")),
            ttlTicks = 40,
        )
        val handle = (gateway.showFor(alice, ttlLayout) as Result.Success).value
        assertThat(gateway.activeFor(alice)).containsExactly(handle)

        // less than ttl — still active
        scheduler.advanceTicks(39)
        assertThat(gateway.activeFor(alice)).containsExactly(handle)

        // crossing the ttl boundary fires the scheduled clear
        scheduler.advanceTicks(1)
        assertThat(gateway.activeFor(alice)).isEmpty()
        // HudCleared event published with the cleared handle
        val cleared = bus.publishedOfType<HudCleared>()
        assertThat(cleared).hasSize(1)
        assertThat(cleared.first().region).isEqualTo(HudRegion.ACTION_BAR)
        assertThat(cleared.first().clearedHandles).containsExactly(handle)
    }

    @Test
    fun `ActionBar TTL fires even after an explicit clearFor leaves nothing to clear`() {
        // belt-and-braces: explicit clearFor before TTL must not crash the scheduler;
        // the TTL callback finds the map empty and is a no-op.
        val ttlLayout = HudLayout.ActionBar(
            component = HudComponent.Text(MessageKey("hud.flash")),
            ttlTicks = 20,
        )
        gateway.showFor(alice, ttlLayout)
        gateway.clearFor(alice, HudRegion.ACTION_BAR) // explicit clear
        // one HudCleared so far (the explicit one)
        assertThat(bus.publishedOfType<HudCleared>()).hasSize(1)

        scheduler.advanceTicks(20)
        // TTL callback finds map empty and does NOT publish another HudCleared
        assertThat(bus.publishedOfType<HudCleared>()).hasSize(1)
    }

    // -------------------------------------------------------------------------
    // FakeHudGateway behaviour
    // -------------------------------------------------------------------------

    @Test
    fun `FakeHudGateway records operations in order`() {
        val fake = FakeHudGateway(eventBus = bus)
        val handle = (fake.showFor(alice, actionBarLayout) as Result.Success).value
        fake.updateFor(handle, sidebarLayout)
        fake.clearFor(alice, HudRegion.SIDEBAR)
        fake.pushResourcePack(alice, packSpec)

        assertThat(fake.operations).hasSize(4)
        assertThat(fake.operations[0]).isInstanceOf(FakeHudGateway.HudOp.Shown::class.java)
        assertThat(fake.operations[1]).isInstanceOf(FakeHudGateway.HudOp.Updated::class.java)
        assertThat(fake.operations[2]).isInstanceOf(FakeHudGateway.HudOp.Cleared::class.java)
        assertThat(fake.operations[3]).isInstanceOf(FakeHudGateway.HudOp.ResourcePackPushed::class.java)
    }

    @Test
    fun `FakeHudGateway simulateClientAcceptedPack records an op without throwing`() {
        val fake = FakeHudGateway(eventBus = bus)
        fake.pushResourcePack(alice, packSpec)

        fake.simulateClientAcceptedPack(alice, packSpec.sha1Hash)

        val accepted = fake.operations
            .filterIsInstance<FakeHudGateway.HudOp.ResourcePackAccepted>()
        assertThat(accepted).hasSize(1)
        assertThat(accepted.first().playerId).isEqualTo(alice)
        assertThat(accepted.first().packHash).isEqualTo(packSpec.sha1Hash)
    }

    @Test
    fun `FakeHudGateway simulateClientDeclinedPack records an op`() {
        val fake = FakeHudGateway()
        fake.simulateClientDeclinedPack(alice, packSpec.sha1Hash)

        val declined = fake.operations
            .filterIsInstance<FakeHudGateway.HudOp.ResourcePackDeclined>()
        assertThat(declined).hasSize(1)
        assertThat(declined.first().packHash).isEqualTo(packSpec.sha1Hash)
    }

    @Test
    fun `FakeHudGateway currentLayout returns the most recent layout for a handle`() {
        val fake = FakeHudGateway()
        val handle = (fake.showFor(alice, actionBarLayout) as Result.Success).value
        assertThat(fake.currentLayout(handle)).isEqualTo(actionBarLayout)

        fake.updateFor(handle, sidebarLayout)
        assertThat(fake.currentLayout(handle)).isEqualTo(sidebarLayout)

        fake.clearFor(alice, HudRegion.SIDEBAR)
        assertThat(fake.currentLayout(handle)).isNull()
    }

    @Test
    fun `FakeHudGateway clear resets operations and active map`() {
        val fake = FakeHudGateway(eventBus = bus)
        fake.showFor(alice, actionBarLayout)
        fake.clear()

        assertThat(fake.operations).isEmpty()
        assertThat(fake.activeFor(alice)).isEmpty()
    }

    @Test
    fun `FakeHudGateway works without an eventBus`() {
        val fake = FakeHudGateway() // no bus
        val handle = (fake.showFor(alice, actionBarLayout) as Result.Success).value

        // no exception, op recorded
        assertThat(fake.operations).hasSize(1)
        assertThat(fake.currentLayout(handle)).isEqualTo(actionBarLayout)
    }

    // -------------------------------------------------------------------------
    // InMemoryCustomFontRegistry
    // -------------------------------------------------------------------------

    @Test
    fun `InMemoryCustomFontRegistry register and lookup round-trip`() {
        val registry = InMemoryCustomFontRegistry()
        val glyph = HudGlyph(
            textureRef = HudGlyphRef("mana-icon"),
            unicodeChar = '',
            widthPx = 8,
            heightPx = 8,
        )

        registry.register(glyph)

        assertThat(registry.lookup(HudGlyphRef("mana-icon"))).isEqualTo(glyph)
        assertThat(registry.lookup(HudGlyphRef("missing-icon"))).isNull()
    }

    @Test
    fun `InMemoryCustomFontRegistry register duplicate unicode char for different ref throws`() {
        val registry = InMemoryCustomFontRegistry()
        val first = HudGlyph(
            textureRef = HudGlyphRef("first"),
            unicodeChar = '',
            widthPx = 8,
            heightPx = 8,
        )
        val collidingChar = HudGlyph(
            textureRef = HudGlyphRef("second"),
            unicodeChar = '', // same char, different ref
            widthPx = 8,
            heightPx = 8,
        )

        registry.register(first)

        assertThatThrownBy { registry.register(collidingChar) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate unicode char")
    }

    @Test
    fun `InMemoryCustomFontRegistry re-registering same ref replaces glyph`() {
        val registry = InMemoryCustomFontRegistry()
        val v1 = HudGlyph(
            textureRef = HudGlyphRef("icon"),
            unicodeChar = '',
            widthPx = 8,
            heightPx = 8,
        )
        val v2 = v1.copy(widthPx = 16, heightPx = 16)

        registry.register(v1)
        registry.register(v2) // same ref + char → idempotent replace, must not throw

        assertThat(registry.lookup(HudGlyphRef("icon"))).isEqualTo(v2)
    }

    @Test
    fun `InMemoryCustomFontRegistry all returns every registered glyph`() {
        val registry = InMemoryCustomFontRegistry()
        val g1 = HudGlyph(HudGlyphRef("a"), '', 8, 8)
        val g2 = HudGlyph(HudGlyphRef("b"), '', 8, 8)
        val g3 = HudGlyph(HudGlyphRef("c"), '', 8, 8)

        registry.register(g1)
        registry.register(g2)
        registry.register(g3)

        assertThat(registry.all()).containsExactlyInAnyOrder(g1, g2, g3)
    }

    @Test
    fun `FakeCustomFontRegistry exposes registeredHistory in order`() {
        val registry = FakeCustomFontRegistry()
        val g1 = HudGlyph(HudGlyphRef("a"), '', 8, 8)
        val g2 = HudGlyph(HudGlyphRef("b"), '', 8, 8)

        registry.register(g1)
        registry.register(g2)

        assertThat(registry.registeredHistory).containsExactly(g1, g2)
    }
}
