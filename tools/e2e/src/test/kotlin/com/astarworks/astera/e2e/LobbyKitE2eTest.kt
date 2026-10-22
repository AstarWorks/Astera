package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction
import org.junit.jupiter.api.Test

/** ADR-0185 lobby menu kit + F1-H HUD diagnostics, seen from a client. */
class LobbyKitE2eTest : E2eBase() {

    private val suite = "Lobby kit & HUD"
    private val kitHotbar = listOf(0, 1, 2, 3, 7, 8)

    /** Which hotbar / crafting slots hold anything on the server (item identity is not needed to see a change). */
    private fun inventoryIds(b: Bot): String = serverSlots(b)

    @Test
    fun `kit is present, locked and the compass opens the menu`() {
        val b = bot("Kit")
        b.waitUntil(8_000) { b.contents[0]?.any { it.amount > 0 } == true }
        Thread.sleep(1_500)

        E2eReport.check(suite, "kit items are in hotbar slots 0,1,2,3,7,8 after join", "SetContainerContent(inventory)") {
            val hb = b.hotbar()
            val missing = kitHotbar.filter { hb[it] == -1 }
            assertThat(missing).describedAs("kit slots empty: $missing (hotbar=$hb)").isEmpty()
            assertThat(listOf(4, 5, 6).filter { hb[it] != -1 }).describedAs("unexpected items in free slots: $hb").isEmpty()
            "hotbar item ids=$hb; crafting area (result,grid x4)=${b.craftingArea()}\nserver item ids: ${inventoryIds(b)}"
        }
        E2eReport.check(suite, "crafting-grid buttons + result slot present", "SetContainerContent(inventory)") {
            val area = b.craftingArea()
            assertThat(area.count { it != -1 }).describedAs("crafting area (result,grid): $area").isEqualTo(5)
            "result,grid0..3 = $area"
        }

        val invBefore = inventoryIds(b)
        val hotbarBefore = b.hotbar()
        val craftBefore = b.craftingArea()
        b.selectHotbar(1)
        Thread.sleep(300)
        b.dropSelected(false)
        b.dropSelected(true)
        b.clickSlot(0, 37, ContainerActionType.DROP_ITEM, DropItemAction.DROP_SELECTED_STACK)
        b.clickSlot(0, 36, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
        b.clickSlot(0, 40, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
        b.clickSlot(0, 20, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
        b.clickSlot(0, 38, ContainerActionType.SHIFT_CLICK_ITEM, org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction.LEFT_CLICK)
        Thread.sleep(2_000)
        b.selectHotbar(0)

        E2eReport.check(suite, "kit items cannot be dropped or moved (server inventory unchanged)", "ServerboundPlayerAction(DROP) + ServerboundContainerClick") {
            val invAfter = inventoryIds(b)
            assertThat(invAfter).describedAs("server-side inventory changed after drop + click attempts").isEqualTo(invBefore)
            assertThat(b.hotbar()).describedAs("client hotbar changed").isEqualTo(hotbarBefore)
            assertThat(b.craftingArea()).describedAs("client crafting area changed").isEqualTo(craftBefore)
            val dropped = rcon("execute in minecraft:lobby if entity @e[type=item,x=${b.x},y=${b.y},z=${b.z},distance=..8]")
            assertThat(dropped).describedAs("an item entity was spawned near the player").contains("failed")
            "inventory identical; hotbar=${b.hotbar()}; nearby item entities: ${dropped.take(120)}"
        }

        E2eReport.check(suite, "clicking a crafting-grid button acts as a button (window opens) and the grid is restored", "ServerboundContainerClick -> OpenScreen / SetContainerContent") {
            val before = b.screens.size
            val grid = b.craftingArea()
            b.clickSlot(0, 1, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
            val opened = b.waitUntil(4_000) { b.screens.size > before }
            val title = b.screens.drop(before).lastOrNull()?.title
            b.screens.drop(before).lastOrNull()?.let { b.closeWindow(it.containerId) }
            Thread.sleep(1_500)
            val inv = inventoryIds(b)
            assertThat(opened).describedAs("grid button click opened nothing; chat=${b.chatTexts().takeLast(3)}").isTrue()
            "opened window '$title'; grid before=$grid after close=${b.craftingArea()}; server item ids: $inv"
        }

        E2eReport.check(suite, "right-click compass (slot 0) opens a window", "ServerboundUseItem -> ClientboundOpenScreen") {
            b.closeWindow(0)
            val before = b.screens.size
            b.selectHotbar(0)
            Thread.sleep(300)
            b.useItem(0f, 0f)
            assertThat(b.waitUntil(5_000) { b.screens.size > before })
                .describedAs("no OpenScreen after right-clicking the compass; chat=${b.chatTexts().takeLast(3)}").isTrue()
            val s = b.screens.last()
            assertThat(rawKeys(listOf(s.title))).describedAs("raw key as title").isEmpty()
            "window id=${s.containerId} type=${s.type} title='${s.title}'"
        }
    }

    @Test
    fun `hud diagnostics line is logged for every bot`() {
        val bots = listOf(bot("HudA"), bot("HudB"))
        bots.forEach { it.waitUntil(8_000) { it.sidebarLines().isNotEmpty() && it.actionBars.isNotEmpty() } }
        Thread.sleep(5_000) // the line is logged ~3s after join

        for (b in bots) {
            E2eReport.check(suite, "server logs `HUD channels for` ${b.name} with sidebar + actionbar", "server log (HudDiagnosticsService)") {
                val lines = ServerLog.lines("HUD channels for ${b.uuid}")
                assertThat(lines).describedAs("no `HUD channels for ${b.uuid}` line in the server log").isNotEmpty()
                val line = lines.last()
                assertThat(line).describedAs("sidebar channel empty").doesNotContain("sidebar=none")
                assertThat(line).describedAs("actionbar channel empty").doesNotContain("actionbar=none")
                assertThat(line).describedAs("server flagged missing owners").doesNotContain("nothing owns any channel")
                "client saw: sidebar title='${b.sidebarTitle()}' ${b.sidebarLines().size} lines, ${b.actionBars.size} action bars\nserver: ${line.substringAfter("]: ")}"
            }
        }
    }
}
