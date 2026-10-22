package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GuiE2eTest : E2eBase() {

    private val suite = "GUI"

    @Test
    fun `menu and admin panel open containers`() {
        val b = bot("GuiOp")
        op(b)
        b.waitUntil(5_000) { b.commandTree != null }
        Thread.sleep(1_000)

        E2eReport.check(suite, "/astera menu opens a container window", "ClientboundOpenScreen + ContainerSetContent") {
            val before = b.screens.size
            b.command("astera menu")
            assertThat(b.waitUntil(5_000) { b.screens.size > before }).describedAs("no OpenScreen packet; chat=${b.chatTexts().takeLast(3)}").isTrue()
            val s = b.screens.last()
            b.waitUntil(3_000) { b.contents[s.containerId]?.any { it.amount > 0 } == true }
            val items = b.contents[s.containerId].orEmpty().count { it.amount > 0 }
            assertThat(s.title).describedAs("window title blank").isNotBlank()
            assertThat(rawKeys(listOf(s.title))).describedAs("raw i18n key as title").isEmpty()
            assertThat(items).describedAs("menu window has no items").isGreaterThan(0)
            b.closeWindow(s.containerId)
            "window id=${s.containerId} type=${s.type} title='${s.title}' slots=${b.contents[s.containerId]?.size} filled=$items"
        }
        E2eReport.check(suite, "/astera admin panel opens the hub (op)", "ClientboundOpenScreen + ContainerSetContent") {
            val before = b.screens.size
            b.command("astera admin panel")
            assertThat(b.waitUntil(5_000) { b.screens.size > before }).describedAs("no OpenScreen packet; chat=${b.chatTexts().takeLast(3)}").isTrue()
            val s = b.screens.last()
            b.waitUntil(3_000) { b.contents[s.containerId]?.any { it.amount > 0 } == true }
            val items = b.contents[s.containerId].orEmpty().count { it.amount > 0 }
            assertThat(rawKeys(listOf(s.title))).describedAs("raw i18n key as title").isEmpty()
            assertThat(items).describedAs("hub has no items").isGreaterThan(0)
            b.closeWindow(s.containerId)
            "window id=${s.containerId} type=${s.type} title='${s.title}' slots=${b.contents[s.containerId]?.size} filled=$items"
        }
        E2eReport.check(suite, "non-op cannot open the admin panel", "ClientboundOpenScreen absent") {
            val p = bot("GuiPlain")
            Thread.sleep(500)
            val before = p.screens.size
            p.command("astera admin panel")
            Thread.sleep(2_000)
            assertThat(p.screens.size).describedAs("non-op got a window: ${p.screens}").isEqualTo(before)
            "no window opened; chat=${p.chatTexts().takeLast(2)}"
        }
    }
}
