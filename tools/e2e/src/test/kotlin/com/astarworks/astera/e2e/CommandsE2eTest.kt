package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommandsE2eTest : E2eBase() {

    private val suite = "Commands"

    private fun Bot.treeVersion() = packetCounts["ClientboundCommandsPacket"]?.get() ?: 0

    @Test
    fun `console and player item commands reach a remote player scope`() {
        val admin = bot("RouteOp")
        val target = bot("RouteTarget")
        op(admin)
        rcon("gamemode creative ${target.name}")
        rcon("astera admin teleport ${target.name} world 4096 100 4096")
        check(target.waitUntil(8_000) { target.x > 4000.0 }) { "target never reached a different region" }
        rcon("clear ${target.name} minecraft:iron_horse_armor")
        Thread.sleep(1_100)
        E2eReport.check(suite, "a player command gives an item to another region and replies to the sender", "Entity(sender) -> Entity(target) -> Entity(sender)") {
            val before = admin.chat.size
            admin.command("astera items give ${target.name} pistol")
            val replies = admin.chatSince(before, 1)
            assertThat(replies).isNotEmpty()
            assertThat(rawKeys(replies)).isEmpty()
            assertThat(target.waitUntil(5_000) {
                rcon("execute if items entity ${target.name} container.* minecraft:iron_horse_armor").contains("passed")
            }).isTrue()
            "remote give replies: ${quote(replies)}"
        }
        E2eReport.check(suite, "console give and refresh fan out to a remote player's inventory", "Global -> Entity(target); refresh future -> sender") {
            rcon("clear ${target.name} minecraft:netherite_sword")
            rcon("astera items give ${target.name} greatsword")
            assertThat(target.waitUntil(5_000) {
                rcon("execute if items entity ${target.name} container.* minecraft:netherite_sword").contains("passed")
            }).describedAs("console give did not reach the remote inventory").isTrue()
            Thread.sleep(1_100)
            val before = admin.chat.size
            admin.command("astera items refresh")
            val replies = admin.chatSince(before, 1)
            assertThat(replies).isNotEmpty()
            assertThat(rawKeys(replies)).isEmpty()
            "refresh replies: ${quote(replies)}"
        }
    }

    @Test
    fun `command tree is permission filtered`() {
        val player = bot("CmdPlayer")
        val admin = bot("CmdOp")
        player.waitUntil(5_000) { player.commandTree != null }
        // `joined` is set on the position packet, which can arrive before Paper's join-time
        // ClientboundCommands. Snapshot the count only once the join tree has landed.
        admin.waitUntil(5_000) { admin.commandTree != null }
        val before = admin.treeVersion()
        op(admin)
        // Wait for the re-sent tree by content (the first packet after /op that shows the op subtrees),
        // not for any packet newer than the snapshot: a late join-time tree would otherwise satisfy it.
        val opTreeArrived = admin.waitUntil(6_000) { "items" in admin.commandTreeChildren("astera") }
        E2eReport.check(suite, "op tree arrives after /op", "ClientboundCommands (re-sent after op)") {
            assertThat(opTreeArrived).describedAs("no ClientboundCommands with op subtrees within 6s after /op (count before=$before, now=${admin.treeVersion()})").isTrue()
            "ClientboundCommands count before=$before after=${admin.treeVersion()}"
        }

        E2eReport.check(suite, "tree root exposes `astera` (non-op)", "ClientboundCommands") {
            assertThat(player.commandTreeRoots()).contains("astera")
            "roots(${player.commandTreeRoots().size}) contain astera; children=${player.commandTreeChildren("astera")}"
        }
        E2eReport.check(suite, "non-op sees player areas only", "ClientboundCommands (requires filtering)") {
            val kids = player.commandTreeChildren("astera")
            assertThat(kids).contains("help", "menu", "queue")
            assertThat(kids).doesNotContain("items", "admin", "mod")
            "astera children (non-op): $kids"
        }
        E2eReport.check(suite, "op additionally sees items/admin/mod/match subtree", "ClientboundCommands (after op, re-sent tree)") {
            val kids = admin.commandTreeChildren("astera")
            assertThat(admin.treeVersion()).describedAs("command tree must be re-sent after /op").isGreaterThan(before)
            assertThat(kids).contains("help", "menu", "queue", "items", "admin", "mod", "match")
            "astera children (op): $kids\nastera items: ${admin.commandTreeChildren("astera", "items")}\nastera match: ${admin.commandTreeChildren("astera", "match")}"
        }
        E2eReport.check(suite, "no legacy hyphenated roots", "ClientboundCommands") {
            val legacy = admin.commandTreeRoots().filter { it.startsWith("astera-") && !it.contains(":") }
            assertThat(legacy).isEmpty()
            "roots with astera prefix: ${admin.commandTreeRoots().filter { it.startsWith("astera") }} (namespaced `astera-core:astera` is Paper's own alias)"
        }
    }

    @Test
    fun `tab completion returns suggestions`() {
        val admin = bot("TabOp")
        op(admin)
        admin.waitUntil(6_000) { admin.commandTree != null }
        Thread.sleep(1_000)

        E2eReport.check(suite, "tab-complete `/astera ` lists areas", "ServerboundCommandSuggestion -> ClientboundCommandSuggestions") {
            val r = admin.complete("/astera ")
            assertThat(r).describedAs("no ClientboundCommandSuggestions reply within 5s").isNotNull()
            assertThat(r!!.matches.toList()).contains("help", "queue", "items")
            "matches=${r.matches.toList()}"
        }
        E2eReport.check(suite, "tab-complete `/astera items give ` suggests players", "ServerboundCommandSuggestion") {
            val r = admin.complete("/astera items give ")
            assertThat(r).describedAs("no reply").isNotNull()
            assertThat(r!!.matches.toList()).contains(admin.name)
            "matches=${r.matches.toList()}"
        }
        E2eReport.check(suite, "tab-complete `/astera items give <bot> ` suggests item ids", "ServerboundCommandSuggestion") {
            val r = admin.complete("/astera items give ${admin.name} ")
            assertThat(r).describedAs("no reply").isNotNull()
            assertThat(r!!.matches.toList()).anyMatch { it.contains("pistol") }
            "matches(${r.matches.size})=${r.matches.take(25)}"
        }
        E2eReport.check(suite, "tab-complete `/astera queue join ` suggests lanes", "ServerboundCommandSuggestion") {
            val r = admin.complete("/astera queue join ")
            assertThat(r).describedAs("no reply").isNotNull()
            assertThat(r!!.matches.toList()).contains("skirmish")
            "matches=${r.matches.toList()}"
        }
        E2eReport.check(suite, "tab-complete `/play ` (shortcut redirect) suggests the same lanes", "ServerboundCommandSuggestion (ADR-0234)") {
            val r = admin.complete("/play ")
            assertThat(r).describedAs("no reply").isNotNull()
            assertThat(r!!.matches.toList()).contains("skirmish")
            "matches=${r.matches.toList()}"
        }
    }

    @Test
    fun `wrong arguments reply with usage and example`() {
        val player = bot("UsagePlayer")
        player.waitUntil(5_000) { player.commandTree != null }
        Thread.sleep(1_000)
        E2eReport.check(suite, "`/astera queue join` without lane shows the expected syntax", "SystemChat (ADR-0234)") {
            val before = player.chat.size
            player.command("astera queue join")
            // The daily login-bonus line can arrive first; wait for the syntax line itself.
            player.waitUntil(6_000) { player.chat.drop(before).any { it.text.contains("/astera queue join <lane>") } }
            val lines = player.chatSince(before)
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            assertThat(lines.joinToString("\n")).contains("/astera queue join <lane>")
            "reply:\n${quote(lines)}"
        }
        E2eReport.check(suite, "`/astera queue` lists the sub-commands", "SystemChat (ADR-0234)") {
            val before = player.chat.size
            player.command("astera queue")
            val lines = player.chatSince(before)
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            assertThat(lines.joinToString("\n")).contains("join").contains("leave")
            "reply:\n${quote(lines)}"
        }
    }

    @Test
    fun `commands execute and reply`() {
        val admin = bot("ExecOp")
        op(admin)
        admin.waitUntil(6_000) { admin.commandTree != null }
        Thread.sleep(1_000)

        fun run(cmd: String, minNew: Int = 1): List<String> {
            val before = admin.chat.size
            admin.command(cmd)
            return admin.chatSince(before, minNew)
        }

        E2eReport.check(suite, "/astera help replies with readable text", "SystemChat") {
            val lines = run("astera help")
            assertThat(lines).describedAs("no chat reply").isNotEmpty()
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            "reply:\n${quote(lines)}"
        }
        E2eReport.check(suite, "/astera queue status replies", "SystemChat") {
            val lines = run("astera queue status")
            assertThat(lines).describedAs("no chat reply").isNotEmpty()
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            "reply:\n${quote(lines)}"
        }
        E2eReport.check(suite, "/astera items give <bot> pistol replies and item arrives", "SystemChat + SetContainerSlot + server inventory") {
            val slotsBefore = admin.slotUpdates.size
            val lines = run("astera items give ${admin.name} pistol")
            admin.waitUntil(4_000) { admin.slotUpdates.size > slotsBefore }
            val inv = entityData(admin.name, "Inventory[].id")
            val hasPistol = entityData(admin.name, "contains:minecraft:iron_horse_armor").toBooleanStrict()
            assertThat(lines).describedAs("no chat reply").isNotEmpty()
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            assertThat(hasPistol).describedAs("server-side inventory has no pistol (IRON_HORSE_ARMOR): $inv").isTrue()
            assertThat(admin.slotUpdates.drop(slotsBefore).any { it.containerId == 0 && it.amount > 0 })
                .describedAs("no SetContainerSlot packet for the player inventory").isTrue()
            "reply:\n${quote(lines)}\nclient slot updates: ${admin.slotUpdates.drop(slotsBefore)}\nserver item ids: ${inv.take(400)} / pistol test: $hasPistol"
        }
        E2eReport.check(suite, "denied command gives readable error and no item", "SystemChat") {
            val plain = bot("CmdNoOp")
            val before = plain.chat.size
            plain.command("astera items give ${plain.name} greatsword") // starter kit already holds a pistol
            val lines = plain.chatSince(before)
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            val hasPistol = entityData(plain.name, "contains:minecraft:netherite_sword").toBooleanStrict()
            assertThat(hasPistol).describedAs("non-op obtained an item through a denied command").isFalse()
            "non-op reply:\n${quote(lines)}"
        }
    }
}
