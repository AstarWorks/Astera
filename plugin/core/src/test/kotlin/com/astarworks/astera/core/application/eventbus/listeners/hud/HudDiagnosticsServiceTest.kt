package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.ResourcePackStatus
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HudDiagnosticsServiceTest {

    private val alice = PlayerId(UUID.randomUUID())
    private val scheduler = FakeScheduler()
    private val hud = FakeHudGateway()
    private val service = HudDiagnosticsService(hud, scheduler, packConfigured = true)

    private fun text(key: String) = HudComponent.Text(MessageKey(key))

    @Test
    fun `a player nothing has drawn for reports every channel as none`() {
        val report = service.describe(alice)
        assertThat(report.sidebar).isEqualTo("none")
        assertThat(report.bossBar).isEqualTo("none")
        assertThat(report.actionBar).isEqualTo("no readout + no status line")
        assertThat(report.pack).isEqualTo("pushed, no client report")
        assertThat(report.line()).startsWith("HUD channels for ${alice.uuid}: sidebar=none, bossbar=none")
    }

    @Test
    fun `the report names what each channel holds and the client's pack status`() {
        hud.showFor(alice, HudLayout.Sidebar(MessageKey("astera.hud.lobby.title"), listOf(text("a"), text("b"))))
        hud.showFor(alice, HudLayout.ActionBar(text("astera.hud.cooldown.summary")))
        hud.showFor(alice, HudLayout.StatusLine(text("astera.hud.status.hp")))
        service.onPackStatus(ResourcePackStatusChanged(alice, ResourcePackStatus.LOADED))

        val report = service.describe(alice)
        assertThat(report.sidebar).isEqualTo("2 lines (astera.hud.lobby.title)")
        assertThat(report.actionBar).isEqualTo("readout(astera.hud.cooldown.summary) + status line (text)")
        assertThat(report.pack).isEqualTo("LOADED")
    }

    @Test
    fun `without a configured pack the report says so`() {
        val none = HudDiagnosticsService(hud, scheduler, packConfigured = false)
        assertThat(none.describe(alice).pack).isEqualTo("not configured")
    }

    @Test
    fun `a join schedules the report without throwing`() {
        service.onJoined(PlayerJoinedServer(alice))
        scheduler.advanceTicks(HudDiagnosticsService.LOG_DELAY_TICKS)
    }
}
