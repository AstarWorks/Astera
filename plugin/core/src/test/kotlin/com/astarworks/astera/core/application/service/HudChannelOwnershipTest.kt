package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.HudShown
import com.astarworks.astera.core.domain.event.HudUpdated
import com.astarworks.astera.core.domain.hud.HudChannelPolicy
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.region
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP14 / ADR-0177: a HUD region is a channel with one live handle per player,
 * and the action bar arbitrates a persistent readout against toasts. Run
 * against both the in-memory and the fake gateway so they cannot drift.
 */
class HudChannelOwnershipTest {

    private val alice = PlayerId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val bob = PlayerId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

    private lateinit var bus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var gateway: InMemoryHudGateway
    private lateinit var fake: FakeHudGateway

    @BeforeEach
    fun setUp() {
        bus = FakeDomainEventBus()
        scheduler = FakeScheduler()
        gateway = InMemoryHudGateway(eventBus = bus, scheduler = scheduler)
        fake = FakeHudGateway(scheduler = scheduler)
    }

    private fun readout(text: String) =
        HudLayout.ActionBar(HudComponent.Text(MessageKey(text)), ttlTicks = 30, priority = 0)

    private fun toast(text: String) =
        HudLayout.ActionBar(HudComponent.Text(MessageKey(text)), ttlTicks = 50, priority = 1)

    private fun subtitle(text: String) = HudLayout.Subtitle(HudComponent.Text(MessageKey(text)))

    private fun sidebar(text: String) = HudLayout.Sidebar(
        titleKey = MessageKey("t"),
        lines = listOf(HudComponent.Text(MessageKey(text))),
    )

    @Test
    fun `showing into an occupied region replaces the handle instead of stacking a second one`() {
        val first = (gateway.showFor(alice, subtitle("a")) as Result.Success).value
        val second = (gateway.showFor(alice, subtitle("b")) as Result.Success).value

        assertThat(second).isEqualTo(first)
        assertThat(gateway.activeFor(alice)).hasSize(1)
        assertThat(bus.publishedOfType<HudShown>()).hasSize(1)
        assertThat(bus.publishedOfType<HudUpdated>()).hasSize(1)
    }

    @Test
    fun `different regions and different players do not share a handle`() {
        gateway.showFor(alice, subtitle("a"))
        gateway.showFor(alice, sidebar("s"))
        gateway.showFor(alice, readout("r"))
        gateway.showFor(bob, subtitle("a"))

        assertThat(gateway.activeFor(alice)).hasSize(3)
        assertThat(gateway.activeFor(bob)).hasSize(1)
    }

    @Test
    fun `a live toast is not overwritten by the persistent readout and the readout returns after it expires`() {
        gateway.showFor(alice, toast("currency"))
        val handle = gateway.activeFor(alice).single()

        gateway.showFor(alice, readout("cooldown"))
        assertThat(bus.publishedOfType<HudUpdated>()).isEmpty()

        scheduler.advanceTicks(50)
        assertThat(gateway.activeFor(alice)).isEmpty() // the toast timed out

        val after = (gateway.showFor(alice, readout("cooldown")) as Result.Success).value
        assertThat(after).isNotEqualTo(handle)
        assertThat(gateway.activeFor(alice)).hasSize(1)
    }

    @Test
    fun `a toast replaces a live readout`() {
        gateway.showFor(alice, readout("cooldown"))
        gateway.showFor(alice, toast("level-up"))

        assertThat(bus.publishedOfType<HudUpdated>().single().layout).isEqualTo(toast("level-up"))
    }

    @Test
    fun `a stale TTL cannot clear a newer action bar on the same handle`() {
        gateway.showFor(alice, readout("a")) // expires at tick 30
        scheduler.advanceTicks(20)
        gateway.showFor(alice, readout("b")) // re-armed: expires at tick 50

        scheduler.advanceTicks(15) // tick 35: the first show's TTL fired and must have been ignored
        assertThat(gateway.activeFor(alice)).hasSize(1)

        scheduler.advanceTicks(15) // tick 50
        assertThat(gateway.activeFor(alice)).isEmpty()
    }

    @Test
    fun `the fake gateway follows the same channel rules`() {
        val first = (fake.showFor(alice, subtitle("a")) as Result.Success).value
        val second = (fake.showFor(alice, subtitle("b")) as Result.Success).value
        assertThat(second).isEqualTo(first)
        assertThat(fake.activeFor(alice)).hasSize(1)

        fake.showFor(alice, toast("t"))
        fake.showFor(alice, readout("r"))
        assertThat(fake.currentLayout(fake.activeFor(alice).first { fake.currentLayout(it) is HudLayout.ActionBar }))
            .isEqualTo(toast("t"))
    }

    @Test
    fun `policy suppresses only lower priority action bars while the higher one is live`() {
        val high = toast("t")
        val low = readout("r")
        assertThat(HudChannelPolicy.suppresses(high, 100, low, nowTick = 10)).isTrue()
        assertThat(HudChannelPolicy.suppresses(high, 100, low, nowTick = 100)).isFalse() // expired
        assertThat(HudChannelPolicy.suppresses(low, 100, high, nowTick = 10)).isFalse()
        assertThat(HudChannelPolicy.suppresses(sidebar("a"), Long.MAX_VALUE, sidebar("b"), nowTick = 0)).isFalse()
    }

    @Test
    fun `region mapping covers every layout`() {
        assertThat(readout("r").region()).isEqualTo(HudRegion.ACTION_BAR)
        assertThat(subtitle("b").region()).isEqualTo(HudRegion.SUBTITLE)
        assertThat(sidebar("s").region()).isEqualTo(HudRegion.SIDEBAR)
    }
}
