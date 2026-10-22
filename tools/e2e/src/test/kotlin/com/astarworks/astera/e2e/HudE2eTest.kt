package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0206: tab header / footer, team-coloured nameplates and HP under the name, as a client receives them. */
class HudE2eTest : E2eBase() {

    private val suite = "HUD"

    @Test
    fun `a hit shows a damage number and a hit marker sound to the attacker only`() {
        val a = bot("HitA")
        val b = bot("HitB")
        b.waitUntil(8_000) { b.actionBars.isNotEmpty() && !b.health.isNaN() }
        op(a)
        op(b)
        Thread.sleep(500)
        val slotsBefore = a.slotUpdates.size
        a.command("astera items give ${a.name} pistol")
        a.waitUntil(4_000) { a.slotUpdates.size > slotsBefore }
        val hotbarIndex = a.slotUpdates.drop(slotsBefore).lastOrNull { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 }?.let { it.slot - 36 } ?: 0
        rcon("tp ${a.name} ${a.x} ${a.y} ${a.z} 0 0")
        rcon("tp ${b.name} ${a.x} ${a.y} ${a.z + 4} 180 0")
        Thread.sleep(1_000)
        a.selectHotbar(hotbarIndex)
        Thread.sleep(500)
        val soundsBefore = a.sounds.size
        val textsBefore = a.entityTexts.size
        val targetTextsBefore = b.entityTexts.size
        repeat(3) {
            a.useItem(0f, 0f)
            Thread.sleep(900)
        }
        val number = Regex("^-\\d+$")
        a.waitUntil(3_000) { a.entityTexts.drop(textsBefore).any { number.matches(it) } }
        Thread.sleep(500)

        E2eReport.check(suite, "attacker gets a floating damage number (text display), the target does not", "ClientboundSetEntityData(text)") {
            val attackerNumbers = a.entityTexts.drop(textsBefore).filter { number.matches(it) }
            val targetNumbers = b.entityTexts.drop(targetTextsBefore).filter { number.matches(it) }
            assertThat(attackerNumbers).describedAs("no '-N' text display for the attacker; texts=${a.entityTexts.drop(textsBefore).take(10)}").isNotEmpty()
            assertThat(targetNumbers).describedAs("the target saw the attacker's damage number").isEmpty()
            "attacker saw $attackerNumbers, target saw $targetNumbers; attacker text displays spawned=${a.spawnedEntityTypes["TEXT_DISPLAY"]}"
        }
        E2eReport.check(suite, "attacker hears the hit marker", "ClientboundSound") {
            val heard = a.sounds.drop(soundsBefore)
            assertThat(heard).describedAs("no sound packet for the attacker after shooting").isNotEmpty()
            "sounds: ${heard.take(6)}"
        }
    }

    @Test
    fun `a kill in a match is announced in chat with names and the weapon`() {
        val a = bot("KillA")
        val b = bot("KillB")
        op(a)
        op(b)
        val posA = a.positionPackets.get()
        val posB = b.positionPackets.get()
        a.command("astera queue join norma")
        b.command("astera queue join norma")
        a.waitUntil(60_000) { a.positionPackets.get() > posA }
        b.waitUntil(30_000) { b.positionPackets.get() > posB }
        Thread.sleep(3_000)
        val slotsBefore = a.slotUpdates.size
        a.command("astera items give ${a.name} pistol")
        a.waitUntil(4_000) { a.slotUpdates.size > slotsBefore }
        val hotbarIndex = a.slotUpdates.drop(slotsBefore).lastOrNull { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 }?.let { it.slot - 36 } ?: 0
        // Face each other on a line at A's position, whatever the arena.
        rcon("tp ${a.name} ${a.x} ${a.y} ${a.z} 0 0")
        rcon("tp ${b.name} ${a.x} ${a.y} ${a.z + 4} 180 0")
        Thread.sleep(1_000)
        a.selectHotbar(hotbarIndex)
        Thread.sleep(500)
        val chatBefore = a.chat.size
        var shots = 0
        while (shots < 60 && a.chatTexts().drop(chatBefore).none { it.contains("[Kill]") || it.contains("[キル]") }) {
            a.useItem(0f, 0f)
            shots++
            Thread.sleep(700)
        }
        Thread.sleep(500)

        E2eReport.check(suite, "kill feed line names killer, victim and weapon", "SystemChat") {
            val lines = a.chatTexts().drop(chatBefore).filter { it.contains("[Kill]") || it.contains("[キル]") }
            assertThat(lines).describedAs("no kill feed line after $shots shots; chat=${a.chatTexts().drop(chatBefore).takeLast(6)}").isNotEmpty()
            val line = lines.first()
            assertThat(line).contains(a.name, b.name)
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            val victimSaw = b.chatTexts().any { it.contains("[Kill]") && it.contains(a.name) && it.contains(b.name) }
            assertThat(victimSaw).describedAs("the victim did not see the kill feed line; victim chat=${b.chatTexts().takeLast(5)}").isTrue()
            "after $shots shots: '$line'"
        }
    }

    @Test
    fun `tab list header and footer in the lobby, then nameplates and tab in a match`() {
        val b = bot("HudTab")
        op(b)
        b.waitUntil(8_000) { b.tabHeader != null && b.sidebarLines().isNotEmpty() }

        E2eReport.check(suite, "lobby tab list has a header and footer", "ClientboundTabList") {
            assertThat(b.tabHeader).describedAs("no tab header received").isNotNull()
            assertThat(b.tabHeader).contains("ASTERA")
            assertThat(b.tabFooter).describedAs("footer").isNotBlank()
            assertThat(b.tabHeader + b.tabFooter).describedAs("raw i18n keys or tags").doesNotContainPattern("astera\\.[a-z-]+\\.|<[a-z/]+>")
            "header='${visible(b.tabHeader.orEmpty())}' footer='${visible(b.tabFooter.orEmpty())}' updates=${b.tabUpdates.get()}"
        }
        E2eReport.check(suite, "lobby players are not team-styled", "ClientboundSetPlayerTeam") {
            assertThat(b.teamOf(b.name)?.first.orEmpty()).describedAs("lobby bot sits in a styled team").doesNotStartWith("ast_np_")
            assertThat(b.belowNameScores()).describedAs("HP under the name in the lobby").isEmpty()
            "teams=${b.teams.keys}"
        }

        val worldsBefore = b.worldHistory.size
        val posBefore = b.positionPackets.get()
        b.command("astera queue join norma")
        b.waitUntil(45_000) { b.positionPackets.get() > posBefore || b.worldHistory.size > worldsBefore }
        b.waitUntil(15_000) { b.teamOf(b.name)?.first?.startsWith("ast_np_") == true }
        b.waitUntil(5_000) { b.belowNameScores().isNotEmpty() }
        Thread.sleep(1_500)

        E2eReport.check(suite, "in a match the player is in a coloured team and HP shows under the name", "SetPlayerTeam(color) + SetDisplayObjective(BELOW_NAME) + SetScore") {
            val team = b.teamOf(b.name)
            assertThat(team).describedAs("no ast_np_ team for ${b.name}; teams=${b.teams.mapValues { it.value.players }}").isNotNull()
            assertThat(team!!.first).startsWith("ast_np_")
            assertThat(team.second.color).describedAs("team colour").isIn("RED", "BLUE", "GREEN", "YELLOW", "GOLD", "AQUA", "LIGHT_PURPLE")
            val hp = b.belowNameScores()[b.name]
            assertThat(hp).describedAs("no HP score under ${b.name}; scores=${b.belowNameScores()}").isNotNull()
            assertThat(hp!!).isGreaterThan(0)
            "team=${team.first} colour=${team.second.color} hp under name=$hp"
        }
        E2eReport.check(suite, "match tab header names the mode, footer the team", "ClientboundTabList") {
            assertThat(b.tabHeader).contains("ASTERA")
            assertThat(b.tabFooter).describedAs("footer should name the viewer's team").contains("|")
            "header='${visible(b.tabHeader.orEmpty())}' footer='${visible(b.tabFooter.orEmpty())}' updates=${b.tabUpdates.get()}"
        }

        val matchId = run {
            val before = b.chat.size
            b.command("astera match list")
            b.chatSince(before, minNew = 2).firstNotNullOfOrNull { Regex("^(\\S+) \\[").find(it)?.groupValues?.get(1) }
        }
        if (matchId != null) b.command("astera match end $matchId")
        b.waitUntil(30_000) { b.belowNameScores().isEmpty() && b.teamOf(b.name)?.first?.startsWith("ast_np_") != true }
        E2eReport.check(suite, "after the match the labels are gone", "SetPlayerTeam(remove) + SetObjective(remove)") {
            assertThat(b.teamOf(b.name)?.first.orEmpty()).doesNotStartWith("ast_np_")
            assertThat(b.belowNameScores()).isEmpty()
            "teams=${b.teams.keys}; below name=${b.belowNameScores()}; header='${visible(b.tabHeader.orEmpty())}'"
        }
    }
}
