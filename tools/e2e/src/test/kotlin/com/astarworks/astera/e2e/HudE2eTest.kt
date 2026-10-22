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
        val displaysBefore = a.spawnedEntityTypes["TEXT_DISPLAY"]?.get() ?: 0
        val targetDisplaysBefore = b.spawnedEntityTypes["TEXT_DISPLAY"]?.get() ?: 0
        repeat(3) {
            a.useItem(0f, 0f)
            Thread.sleep(900)
        }
        a.waitUntil(3_000) { (a.spawnedEntityTypes["TEXT_DISPLAY"]?.get() ?: 0) > displaysBefore }
        Thread.sleep(500)

        E2eReport.check(suite, "attacker gets a floating damage number (text display), the target does not", "ClientboundAddEntity(TEXT_DISPLAY)") {
            val attackerGot = (a.spawnedEntityTypes["TEXT_DISPLAY"]?.get() ?: 0) - displaysBefore
            val targetGot = (b.spawnedEntityTypes["TEXT_DISPLAY"]?.get() ?: 0) - targetDisplaysBefore
            assertThat(attackerGot).describedAs("no TEXT_DISPLAY spawned for the attacker; spawned=${a.spawnedEntityTypes}").isGreaterThan(0)
            assertThat(targetGot).describedAs("the target saw the attacker's damage number").isZero()
            "attacker saw $attackerGot damage number(s), target $targetGot; attacker entity types=${a.spawnedEntityTypes}"
        }
        E2eReport.check(suite, "attacker hears the hit marker", "ClientboundSound") {
            val heard = a.sounds.drop(soundsBefore)
            assertThat(heard).describedAs("no sound packet for the attacker after shooting").isNotEmpty()
            "sounds: ${heard.take(6)}"
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
