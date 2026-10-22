package com.astarworks.astera.e2e.live

import com.astarworks.astera.e2e.E2eReport
import com.astarworks.astera.e2e.rawKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `make live-bots SCENARIO=match`: the whole loop on the live stack. Two forwarded bots queue `norma` on the lobby, the
 * queue hands them to the game backend (the bots emulate Velocity's `Connect` hop), a match runs, bot A kills bot B,
 * B respawns, an admin (RCON console) ends the match and both return to the lobby with the reward message.
 *
 * Refuses to run while another match is on the game backend (a real player's match is never touched).
 */
class MatchLiveTest : LiveBase() {

    private val suite = "Live match"

    @Test
    fun `queue norma - play - kill - respawn - end - back to lobby`() {
        val gameMark = LogMark("game")
        val lobbyMark = LogMark("lobby")
        val before = matchLines()
        E2eReport.check(suite, "game backend has no other running match (precondition)", "RCON astera match list") {
            assertThat(before.filter { matchIdOf(it) != null }).describedAs("other matches are running; refusing to interfere:\n${quote(before)}").isEmpty()
            "list: $before"
        }
        if (before.any { matchIdOf(it) != null }) return

        val a = bot("mata")
        val b = bot("matb")
        val lobbyKit = a.hotbar().ifEmpty { listOf() }
        val matchId = enterNormaMatch(suite, a, b, gameMark, lobbyMark)
        if (matchId == null) {
            E2eReport.notes += "match scenario stopped early: no ACTIVE match"
            return
        }
        E2eReport.check(suite, "bots see the match HUD (sidebar, no lobby kit)", "SetObjective/SetScore + inventory") {
            a.waitUntil(8_000) { a.sidebarLines().isNotEmpty() }
            assertThat(a.sidebarLines()).describedAs("empty match sidebar").isNotEmpty()
            assertThat(rawKeys(a.sidebarLines())).isEmpty()
            "A sidebar '${a.sidebarTitle()}':\n${quote(a.sidebarLines())}\nhotbar in match=${a.hotbar()} pos=(${a.x.toInt()},${a.y.toInt()},${a.z.toInt()}) world=${a.worldName}"
        }

        // --- A kills B ------------------------------------------------------------------------------------------
        rcon("game", "clear ${a.name}")
        val slotsBefore = a.slotUpdates.size
        rcon("game", "astera items give ${a.name} pistol")
        a.waitUntil(5_000) { a.slotUpdates.drop(slotsBefore).any { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 } }
        val slot = a.slotUpdates.drop(slotsBefore).lastOrNull { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 }?.slot?.minus(36) ?: 0
        a.selectHotbar((slot + 1) % 9); Thread.sleep(300); a.selectHotbar(slot); Thread.sleep(500)
        val deathsBefore = b.deaths.size
        val worldsB = b.worldHistory.size
        val modesB = b.gameModes.size
        var shots = 0
        fun aimAtB() = rcon("game", "execute at ${b.name} run tp ${a.name} ~ ~ ~-3 facing entity ${b.name} feet")
        while (shots < 60 && b.deaths.size == deathsBefore) {
            aimAtB()
            Thread.sleep(250)
            a.useItem(0f, 0f)
            Thread.sleep(700)
            shots++
            if (shots % 10 == 0) rcon("game", "astera items give ${a.name} pistol")
        }
        b.waitUntil(5_000) { b.deaths.size > deathsBefore }
        val teamHint = rcon("game", "team list").take(300)
        var forced = false
        if (b.deaths.size == deathsBefore) {
            // Fallback so the rest of the loop is still exercised; the missing PvP kill is reported as a failure below.
            rcon("game", "damage ${b.name} 1000 minecraft:player_attack by ${a.name}")
            forced = true
            b.waitUntil(5_000) { b.deaths.size > deathsBefore }
        }
        E2eReport.check(suite, "bot A kills bot B with weapon shots (no forced damage needed)", "ClientboundSetHealth / PlayerCombatKill") {
            assertThat(forced).describedAs("pistol shots (x$shots) never killed B; forced with /damage. B health=${b.health} A chat=${a.chatTexts().takeLast(4)} teams: $teamHint").isFalse()
            "B died after $shots shot(s); death message=${b.deaths.drop(deathsBefore)}"
        }
        E2eReport.check(suite, "B gets the death screen / spectator state", "ClientboundPlayerCombatKill + GameEvent(CHANGE_GAME_MODE)") {
            assertThat(b.deaths.size).describedAs("no death screen packet; B health history tail=${b.healthHistory.takeLast(5)}").isGreaterThan(deathsBefore)
            b.waitUntil(4_000) { b.gameModes.size > modesB }
            "death screen: ${b.deaths.drop(deathsBefore)}; game modes since: ${b.gameModes.drop(modesB)}; chat B tail=${b.chatTexts().takeLast(4)}"
        }
        // The death screen waits for the Respawn button; the plugin may auto-respawn or switch to spectator first.
        val respawnedAuto = b.waitUntil(6_000) { b.worldHistory.size > worldsB || b.health > 0f }
        if (!respawnedAuto || b.health == 0f) b.respawn()
        val respawned = b.waitUntil(15_000) { b.health > 0f && b.joined }
        E2eReport.check(suite, "B respawns (or becomes a spectator) and is playable again", "ClientboundRespawn / SetHealth") {
            assertThat(respawned).describedAs("B never came back: health=${b.health} joined=${b.joined} disconnect=${b.disconnectReason} modes=${b.gameModes}").isTrue()
            "health=${b.health} worlds=${b.worldHistory.drop(worldsB)} modes=${b.gameModes.drop(modesB)} auto=$respawnedAuto"
        }

        // --- end the match, everybody back to the lobby ------------------------------------------------------------
        val chatEndA = a.chat.size
        val chatEndB = b.chat.size
        val endOut = rcon("game", "astera match end $matchId").replace(Regex("\u001B\\[[0-9;]*m"), "")
        val back = listOf(a, b).all { p -> p.waitUntil(45_000) { "lobby" in p.hops } }
        E2eReport.check(suite, "after /astera match end the server sends both bots back (BungeeCord Connect -> lobby)", "BungeeCord plugin message") {
            assertThat(back).describedAs(
                "no Connect->lobby within 45s. end output='${endOut.trim()}' hops A=${a.hops} B=${b.hops}; game log:\n" +
                    quote(gameMark.lines().filter { it.contains("atch") || it.contains("ransfer") || it.contains("not implemented") }.takeLast(10)),
            ).isTrue()
            "end output: '${endOut.trim()}'; hops A=${a.hops} B=${b.hops}"
        }
        if (!back) listOf(a, b).forEach { p -> p.forceHop("lobby") }
        listOf(a, b).forEach { p -> p.waitUntil(30_000) { p.joined && serverOf(p) == "lobby" } }
        Thread.sleep(4_000)
        E2eReport.check(suite, "both bots are on the lobby again with kit and sidebar", "SetContainerContent + SetScore") {
            assertThat(listOf(a, b).all { it.joined && serverOf(it) == "lobby" }).describedAs("not both on lobby: A=${a.joined}/${a.disconnectReason} B=${b.joined}/${b.disconnectReason}").isTrue()
            a.waitUntil(8_000) { a.hotbar().any { it != -1 } && a.sidebarLines().isNotEmpty() }
            assertThat(a.hotbar().any { it != -1 }).describedAs("kit missing on return: ${a.hotbar()}").isTrue()
            assertThat(a.sidebarLines()).describedAs("sidebar empty on return").isNotEmpty()
            "hotbar A=${a.hotbar()} B=${b.hotbar()} (lobby kit before: $lobbyKit); sidebar A=${a.sidebarLines()}"
        }
        E2eReport.check(suite, "reward/result message reaches the players (Reward: +N currency (+N XP))", "SystemChat") {
            val linesA = a.chat.drop(chatEndA).map { it.text }
            val linesB = b.chat.drop(chatEndB).map { it.text }
            val all = a.chatTexts() + b.chatTexts()
            assertThat(all.any { Regex("Reward: \\+\\d+").containsMatchIn(it) })
                .describedAs("no 'Reward: +N' line in either bot's chat:\nA:\n${quote(a.chatTexts(), 40)}\nB:\n${quote(b.chatTexts(), 40)}").isTrue()
            assertThat(rawKeys(all)).describedAs("raw i18n keys in chat").isEmpty()
            "A since end:\n${quote(linesA)}\nB since end:\n${quote(linesB)}"
        }
        E2eReport.check(suite, "server logs have no errors/exceptions during the scenario", "server logs") {
            val errs = gameMark.errors().map { "[game] $it" } + lobbyMark.errors().map { "[lobby] $it" }
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
