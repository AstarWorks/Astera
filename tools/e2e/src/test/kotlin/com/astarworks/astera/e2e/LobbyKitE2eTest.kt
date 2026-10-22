package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction
import org.junit.jupiter.api.Test

/** ADR-0185 lobby menu kit + F1-H HUD diagnostics, seen from a client. */
class LobbyKitE2eTest : E2eBase() {

    private val suite = "Lobby kit & HUD"
    /** HotbarLayout (ADR-0232): the kit is ONE item (the compass, slot 8); slots 0..7 belong to the player / weapons. */
    private val kitHotbar = listOf(8)
    private val weaponZone = (0..7).toList()

    /** Which hotbar / crafting slots hold anything on the server (item identity is not needed to see a change). */
    private fun inventoryIds(b: Bot): String = serverSlots(b)

    @Test
    fun `kit is present, locked and the compass opens the menu`() {
        val b = bot("Kit")
        b.waitUntil(8_000) { b.contents[0]?.any { it.amount > 0 } == true }
        Thread.sleep(1_500)

        E2eReport.check(suite, "exactly one kit item (compass, slot 8) after join, weapons (starter grant) in 0..7, no other menu item", "SetContainerContent(inventory)") {
            val hb = b.hotbar()
            val compass = hb[8]
            assertThat(weaponZone.filter { hb[it] == compass }).describedAs("compass id also in the weapon zone: $hb").isEmpty()
            val missing = kitHotbar.filter { hb[it] == -1 }
            assertThat(missing).describedAs("kit slots empty: $missing (hotbar=$hb)").isEmpty()
            val kitIds = kitHotbar.map { hb[it] }.toSet()
            assertThat(weaponZone.filter { hb[it] in kitIds }).describedAs("kit item in the weapon zone: $hb").isEmpty()
            assertThat(weaponZone.count { hb[it] != -1 }).describedAs("starter weapons missing from the weapon zone: $hb").isGreaterThanOrEqualTo(1)
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
        val screensBefore = b.screens.size
        b.selectHotbar(8)
        Thread.sleep(300)
        b.dropSelected(false)
        b.dropSelected(true)
        b.clickSlot(0, 41, ContainerActionType.DROP_ITEM, DropItemAction.DROP_SELECTED_STACK)
        b.clickSlot(0, 44, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
        b.clickSlot(0, 42, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
        b.clickSlot(0, 20, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
        b.clickSlot(0, 43, ContainerActionType.SHIFT_CLICK_ITEM, org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction.LEFT_CLICK)
        Thread.sleep(2_000)
        // Q on the compass is the queue keybind (ADR-0232), so it may open a window; close it before going on.
        b.screens.drop(screensBefore).forEach { b.closeWindow(it.containerId) }
        Thread.sleep(500)
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

        E2eReport.check(suite, "right-click compass (hotbar slot 8) opens a window", "ServerboundUseItem -> ClientboundOpenScreen") {
            b.closeWindow(0)
            val before = b.screens.size
            b.selectHotbar(8)
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
    fun `swap-hand keybinds open the menu when no weapon is held and never touch the hand otherwise`() {
        val b = bot("Keys")
        b.waitUntil(8_000) { b.contents[0]?.any { it.amount > 0 } == true }
        Thread.sleep(1_500)

        E2eReport.check(suite, "F with an empty hand (slot 7) opens the main menu", "ServerboundPlayerAction(SWAP_HANDS) -> OpenScreen") {
            b.closeWindow(0)
            b.selectHotbar(7)
            Thread.sleep(300)
            assertThat(b.hotbar()[7]).describedAs("slot 7 must be free for this check: ${b.hotbar()}").isEqualTo(-1)
            val before = b.screens.size
            b.swapHands()
            assertThat(b.waitUntil(5_000) { b.screens.size > before }).describedAs("F opened nothing; chat=${b.chatTexts().takeLast(3)}").isTrue()
            val s = b.screens.last()
            b.closeWindow(s.containerId)
            assertThat(rawKeys(listOf(s.title))).describedAs("raw key as title").isEmpty()
            "window id=${s.containerId} title='${s.title}'"
        }
        E2eReport.check(suite, "Sneak+F with an empty hand opens a different window (vault / loadout)", "SWAP_HANDS while sneaking -> OpenScreen") {
            Thread.sleep(500)
            val before = b.screens.size
            b.sneak(true)
            Thread.sleep(300)
            b.swapHands()
            val opened = b.waitUntil(5_000) { b.screens.size > before }
            b.sneak(false)
            assertThat(opened).describedAs("Sneak+F opened nothing; chat=${b.chatTexts().takeLast(3)}").isTrue()
            val s = b.screens.last()
            b.closeWindow(s.containerId)
            "window id=${s.containerId} title='${s.title}'"
        }
        E2eReport.check(suite, "F while holding a weapon does not open a menu (weapons keep their hand)", "SWAP_HANDS with a weapon selected") {
            Thread.sleep(500)
            val weaponSlot = (0..7).first { b.hotbar()[it] != -1 }
            b.selectHotbar(weaponSlot)
            Thread.sleep(300)
            val before = b.screens.size
            b.swapHands()
            Thread.sleep(1_500)
            assertThat(b.screens.size).describedAs("F with a weapon in hand opened a window").isEqualTo(before)
            "weapon slot=$weaponSlot, hotbar=${b.hotbar()}"
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
