package com.astarworks.astera.core.application.service.lobby

import com.astarworks.astera.core.application.config.LobbyMenuKitEntryYaml
import com.astarworks.astera.core.application.config.LobbyMenuKitYamlConfig
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.lobby.HotbarLayout
import com.astarworks.astera.core.domain.lobby.KitArea
import com.astarworks.astera.core.domain.lobby.LobbyKeybinds
import com.astarworks.astera.core.domain.lobby.LobbyMenuKit
import com.astarworks.astera.core.domain.lobby.LobbyMenuKitPolicy
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.proxy.ServerRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Kit layout validation, the lobby/match policy and the shipped `content/gui/kit/lobby-kit.yaml`. */
class LobbyMenuKitTest {

    private val content: Path = locateContentRoot()
    private val kit: LobbyMenuKit = (LobbyMenuKitContent.load(content) as Result.Success).value

    private fun yaml(id: String, area: String, slot: Int, intent: String = "lobby.queue.show") =
        LobbyMenuKitEntryYaml(id = id, area = area, slot = slot, item = "menu-compass", intent = intent)

    private fun build(vararg entries: LobbyMenuKitEntryYaml) =
        LobbyMenuKitContent.build(LobbyMenuKitYamlConfig(entries.toList()))

    @Test
    fun `shipped kit has the compass in hotbar slot 8 opening the main menu`() {
        val compass = kit.at(KitArea.HOTBAR, 8)!!
        assertThat(compass.itemId.value).isEqualTo("menu-compass")
        assertThat(compass.intent.key).isEqualTo("gui.open")
        assertThat(compass.intent.payload).containsEntry("gui", "main-menu")
    }

    @Test
    fun `shipped kit fills the whole 2x2 grid and the result slot`() {
        assertThat(kit.entries(KitArea.CRAFTING_GRID).map { it.slot }).containsExactlyInAnyOrder(0, 1, 2, 3)
        assertThat(kit.at(KitArea.CRAFTING_RESULT, 0)?.intent?.key).isEqualTo("lobby.queue.show")
        assertThat(kit.hasCraftingButtons).isTrue()
    }

    @Test
    fun `button to intent mapping resolves through the entry id carried by the item`() {
        assertThat(kit.find("grid-shop")?.intent?.key).isEqualTo("lobby.shop.open")
        assertThat(kit.find("grid-profile")?.intent?.key).isEqualTo("lobby.profile.show")
        assertThat(kit.find("grid-settings")?.intent?.key).isEqualTo("lobby.settings.show")
        assertThat(kit.find("grid-menu")?.intent?.payload).containsEntry("gui", "main-menu")
        assertThat(kit.find("nope")).isNull()
    }

    @Test
    fun `every shipped item and hint key exists in content`() {
        val items = content.resolve("items")
        val en = Files.readString(content.resolve("languages/en.yaml"))
        val ja = Files.readString(content.resolve("languages/ja.yaml"))
        for (e in kit.entries) {
            assertThat(Files.isRegularFile(items.resolve("${e.itemId.value}.yaml"))).`as`("item ${e.itemId.value}").isTrue()
            if (!e.area.isKeybind) assertThat(e.loreKey).isNotNull()
        }
        assertThat(en).contains("lobby-kit:")
        assertThat(ja).contains("lobby-kit:")
    }

    @Test
    fun `shipped hotbar kit is exactly one item and leaves the weapon zone free`() {
        val slots = kit.entries(KitArea.HOTBAR).map { it.slot }
        assertThat(slots).containsExactly(8)
        assertThat(slots.size).isLessThanOrEqualTo(HotbarLayout.MAX_KIT_ITEMS)
        assertThat(HotbarLayout.WEAPON_SLOTS.count()).isEqualTo(8)
    }

    @Test
    fun `shipped keybinds - F menu, sneak+F customize, Q on compass queue`() {
        assertThat(kit.at(KitArea.KEY_SWAP, 0)?.intent?.payload).containsEntry("gui", "main-menu")
        assertThat(kit.at(KitArea.KEY_SNEAK_SWAP, 0)?.intent?.key).isEqualTo("lobby.customize.open")
        assertThat(kit.at(KitArea.KEY_DROP, 0)?.intent?.key).isEqualTo("lobby.queue.show")
        assertThat(kit.entries(KitArea.HOTBAR).size + kit.entries.count { it.area.isKeybind }).isEqualTo(4)
    }

    @Test
    fun `a second hotbar item is rejected`() {
        assertThat(build(yaml("a", "hotbar", 8), yaml("b", "hotbar", 7))).isInstanceOf(Result.Failure::class.java)
        assertThat(build(yaml("a", "hotbar", 8), yaml("b", "key_swap", 0), yaml("c", "key_drop", 0)))
            .isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `swap key opens a keybind only when no weapon is held`() {
        assertThat(LobbyKeybinds.swapArea(holdingWeapon = false, sneaking = false)).isEqualTo(KitArea.KEY_SWAP)
        assertThat(LobbyKeybinds.swapArea(holdingWeapon = false, sneaking = true)).isEqualTo(KitArea.KEY_SNEAK_SWAP)
        assertThat(LobbyKeybinds.swapArea(holdingWeapon = true, sneaking = false)).isNull()
        assertThat(LobbyKeybinds.swapArea(holdingWeapon = true, sneaking = true)).isNull()
    }

    @Test
    fun `keybind entries do not count as crafting buttons`() {
        assertThat(LobbyMenuKitContent.build(LobbyMenuKitYamlConfig(listOf(yaml("k", "key_swap", 0)))).let { (it as Result.Success).value.hasCraftingButtons }).isFalse()
    }

    @Test
    fun `a kit entry in the weapon zone is rejected`() {
        assertThat(build(yaml("a", "hotbar", 0))).isInstanceOf(Result.Failure::class.java)
        assertThat(build(yaml("a", "hotbar", 4))).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `weapons take the first free weapon-zone slot while the kit is active, any slot in a match`() {
        assertThat(HotbarLayout.firstFreeSlot(setOf(0, 1, 8), kitActive = true)).isEqualTo(2)
        assertThat(HotbarLayout.firstFreeSlot(setOf(0, 1, 2, 3, 4, 5, 6, 7, 8), kitActive = true)).isNull()
        assertThat(HotbarLayout.firstFreeSlot(setOf(0, 1, 2, 3, 4, 5, 6, 7), kitActive = true)).isNull()
        assertThat(HotbarLayout.firstFreeSlot(setOf(0, 1, 2, 3, 4, 5, 6, 7), kitActive = false)).isEqualTo(8)
    }

    @Test
    fun `hotbar slots stay in 0 to 8 and every slot is used once`() {
        assertThat(build(yaml("a", "hotbar", 9))).isInstanceOf(Result.Failure::class.java)
        assertThat(build(yaml("a", "hotbar", 8), yaml("b", "hotbar", 8))).isInstanceOf(Result.Failure::class.java)
        assertThat(build(yaml("a", "hotbar", 8), yaml("a", "hotbar", 7))).isInstanceOf(Result.Failure::class.java)
        assertThat(build(yaml("a", "key_swap", 1))).isInstanceOf(Result.Failure::class.java)
        assertThat(build(yaml("a", "crafting_grid", 4))).isInstanceOf(Result.Failure::class.java)
        assertThat(build(yaml("a", "crafting_result", 1))).isInstanceOf(Result.Failure::class.java)
        assertThat(build(yaml("a", "sideways", 0))).isInstanceOf(Result.Failure::class.java)
        assertThat(build(yaml("a", "hotbar", 8), yaml("b", "crafting_grid", 1))).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `a malformed intent key is reported, not thrown`() {
        assertThat(build(yaml("a", "hotbar", 8, intent = "Bad Key"))).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `missing kit file reports Missing`() {
        val r = LobbyMenuKitContent.load(content.resolve("does-not-exist"))
        assertThat((r as Result.Failure).error).isEqualTo(LobbyMenuKitError.Missing)
    }

    @Test
    fun `kit is for lobby hosts and never inside a match`() {
        assertThat(LobbyMenuKitPolicy.active(ServerRole.LOBBY, inMatch = false)).isTrue()
        assertThat(LobbyMenuKitPolicy.active(ServerRole.ALL, inMatch = false)).isTrue()
        assertThat(LobbyMenuKitPolicy.active(ServerRole.ALL, inMatch = true)).isFalse()
        assertThat(LobbyMenuKitPolicy.active(ServerRole.GAME, inMatch = false)).isFalse()
    }

    @Test
    fun `F3-10 match membership counts as in match even without a snapshot hold`() {
        assertThat(LobbyMenuKitPolicy.inMatch(snapshotHeld = false, membershipPhase = null)).isFalse()
        for (phase in MatchPhase.entries.filter { it != MatchPhase.ENDED }) {
            val inMatch = LobbyMenuKitPolicy.inMatch(snapshotHeld = false, membershipPhase = phase)
            assertThat(inMatch).`as`("phase $phase").isTrue()
            assertThat(LobbyMenuKitPolicy.active(ServerRole.ALL, inMatch)).isFalse()
        }
        assertThat(LobbyMenuKitPolicy.inMatch(snapshotHeld = false, membershipPhase = MatchPhase.ENDED)).isFalse()
        assertThat(LobbyMenuKitPolicy.inMatch(snapshotHeld = true, membershipPhase = null)).isTrue()
    }

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("repo root (settings.gradle.kts + content/) not found")
    }
}
