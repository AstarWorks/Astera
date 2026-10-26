package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Packet-only HUD session lifecycle, also selectable for the isolated Folia boot probe. */
class PacketHudE2eTest : E2eBase() {
    @Test
    fun `a reconnect gets fresh sidebar and tab state`() {
        val first = bot("PacketHud")
        assertThat(first.waitUntil(15_000) { first.sidebarLines().isNotEmpty() && first.tabHeader != null }).isTrue()
        val title = first.sidebarTitle()
        val name = first.name
        val observer = bot("TabView")
        observer.waitUntil(8_000) { observer.tabDisplayNames[first.uuid] == name }
        E2eReport.check("HUD", "cosmetic tab names reach existing and newly joined viewers", "PlayerInfoUpdate(UPDATE_DISPLAY_NAME)") {
            assertThat(observer.tabDisplayNames[first.uuid]).isEqualTo(name)
            assertThat(first.waitUntil(8_000) { first.tabDisplayNames[observer.uuid] == observer.name }).isTrue()
            "both viewers received each other's packet-rendered tab name"
        }
        first.close()
        Thread.sleep(1_000)
        val back = bot("PacketHud", exactName = name)
        back.waitUntil(15_000) { back.sidebarLines().isNotEmpty() && back.tabHeader != null && back.tabDisplayNames[observer.uuid] == observer.name }
        E2eReport.check("HUD", "reconnect creates a fresh packet sidebar and tab header", "SetObjective + SetScore + SetPlayerTeam + TabList") {
            assertThat(back.sidebarTitle()).isEqualTo(title).isNotBlank()
            assertThat(back.sidebarLines()).isNotEmpty()
            assertThat(back.tabHeader).contains("ASTERA")
            assertThat(back.objectiveCreates["astera"]?.get()).isEqualTo(1)
            assertThat(back.belowNameScores()).isEmpty()
            assertThat(back.tabDisplayNames[observer.uuid]).isEqualTo(observer.name)
            "sidebar='${back.sidebarTitle()}', lines=${back.sidebarLines()}, header='${back.tabHeader}'"
        }
    }
}
