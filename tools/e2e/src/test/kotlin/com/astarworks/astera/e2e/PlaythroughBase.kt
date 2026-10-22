package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat

/**
 * G1 helpers for the playthrough suites: real matches played end to end by protocol bots.
 *
 * Bots are driven like players where that is cheap (queue command, hotbar select, right click, Shift+Q reload,
 * START/FINISH_DIGGING) and positioned by RCON `tp` where a real player would walk and aim (the arena is flat and the
 * targets stand still, so a fixed "3 blocks behind the target, looking at it" pose is the deterministic stand-in).
 * Server truth (positions, game mode) is read over RCON; everything a player *sees* (sidebar, chat, action bar) is read
 * from the bot's own packets.
 */
abstract class PlaythroughBase : E2eBase() {

    protected data class MatchLine(val id: String, val phase: String, val raw: String)

    protected fun clean(s: String): String = s.replace(Regex("§.|\u001B\\[[0-9;]*m"), "")

    protected fun matchLines(): List<MatchLine> =
        clean(rcon("astera match list")).lineSequence().mapNotNull { line ->
            Regex("(match-[0-9a-f]+) \\[(\\w+)]").find(line)?.let { MatchLine(it.groupValues[1], it.groupValues[2], line.trim()) }
        }.toList()

    /** Polls `astera match list` until a match is in [phase]; returns it, or null on timeout. */
    protected fun awaitMatch(phase: String, timeoutMs: Long, keepAlive: List<Bot> = emptyList()): MatchLine? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            matchLines().firstOrNull { it.phase.equals(phase, ignoreCase = true) }?.let { return it }
            keepAlive.forEach { it.nudge() }
            Thread.sleep(500)
        }
        return null
    }

    /** True once no match is listed any more (the match ended and was cleaned up). */
    protected fun awaitNoMatch(timeoutMs: Long, keepAlive: List<Bot> = emptyList()): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (matchLines().isEmpty()) return true
            keepAlive.forEach { it.nudge() }
            Thread.sleep(500)
        }
        return matchLines().isEmpty()
    }

    protected fun posOf(b: Bot): Triple<Double, Double, Double>? =
        Regex("\\[(-?[0-9.]+)d, (-?[0-9.]+)d, (-?[0-9.]+)d]").find(rcon("data get entity ${b.name} Pos"))?.let {
            Triple(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble())
        }

    /** Server-side game mode id (0 survival, 1 creative, 2 adventure, 3 spectator), or null. */
    protected fun gameTypeOf(b: Bot): Int? =
        Regex("(-?\\d+)\\s*$").find(clean(rcon("data get entity ${b.name} playerGameType")).trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Gives [item] to the bot over the console, selects it and returns the hotbar index (null if it never arrived). */
    protected fun equip(b: Bot, item: String): Int? {
        rcon("clear ${b.name}")
        val before = b.slotUpdates.size
        rcon("astera items give ${b.name} $item")
        b.waitUntil(5_000) { b.slotUpdates.drop(before).any { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 } }
        val slot = b.slotUpdates.drop(before).lastOrNull { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 }?.slot?.minus(36)
            ?: return null
        b.selectHotbar((slot + 1) % 9)
        Thread.sleep(150)
        b.selectHotbar(slot)
        Thread.sleep(300)
        return slot
    }

    protected fun status(b: Bot): String = visible(b.actionBars.lastOrNull()?.text.orEmpty())

    protected fun ammoOf(b: Bot): Int? = Regex("Ammo\\s*(\\d+)/(\\d+)").find(status(b))?.groupValues?.get(1)?.toIntOrNull()

    /** Shift+Q: sneak, drop key (the plugin cancels the drop), release sneak, wait out the reload. */
    protected fun reload(shooter: Bot) {
        shooter.sneak(true)
        Thread.sleep(150)
        shooter.dropSelected()
        Thread.sleep(250)
        shooter.sneak(false)
        Thread.sleep(1_700)
    }

    protected class Duel(val shots: Int, val reloads: Int, val killed: Boolean, val millis: Long)

    /**
     * [shooter] fires the pistol at [victim] (3 blocks in front of the muzzle, chest height) until the victim's death
     * screen arrives. Reloads when the status line says the magazine is empty (or after 13 shots without a reading).
     */
    protected fun duel(shooter: Bot, victim: Bot, maxShots: Int = 90, keepAlive: List<Bot> = emptyList()): Duel {
        val deathsBefore = victim.deaths.size
        val t0 = System.nanoTime()
        var shots = 0
        var sinceReload = 0
        var reloads = 0
        while (shots < maxShots && victim.deaths.size == deathsBefore) {
            rcon("execute at ${victim.name} run tp ${shooter.name} ~ ~ ~-3 0 12")
            Thread.sleep(120)
            val ammo = ammoOf(shooter)
            if ((ammo != null && ammo <= 0) || sinceReload >= 13) {
                reload(shooter)
                reloads++
                sinceReload = 0
                continue
            }
            shooter.useItem(0f, 12f)
            shots++
            sinceReload++
            keepAlive.forEach { it.nudge() }
            Thread.sleep(330)
        }
        val killed = victim.waitUntil(4_000) { victim.deaths.size > deathsBefore }
        return Duel(shots, reloads, killed, (System.nanoTime() - t0) / 1_000_000)
    }

    /** Milliseconds from now until [b] plays again (server game mode back to survival/adventure), or null on timeout. */
    protected fun awaitRespawn(b: Bot, timeoutMs: Long = 25_000): Long? {
        val t0 = System.nanoTime()
        // The death screen puts the victim into spectator for the respawn countdown; wait for that first so a stale
        // "survival" read just after the kill is not mistaken for the respawn.
        val spectated = b.waitUntil(5_000) { gameTypeOf(b) == 3 }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val gt = gameTypeOf(b)
            if (gt != null && gt != 3 && (spectated || System.nanoTime() - t0 > 3_000_000_000L)) return (System.nanoTime() - t0) / 1_000_000
            Thread.sleep(400)
        }
        return null
    }

    /** Sidebar of [b] as plain text lines. */
    protected fun sidebar(b: Bot): List<String> = b.sidebarLines().map { visible(it) }

    /** First number on the sidebar line that mentions [team] (e.g. `Red: 2` -> 2), or null. */
    protected fun scoreOf(b: Bot, team: String): Int? =
        sidebar(b).firstOrNull { it.contains(team, ignoreCase = true) }?.let { Regex("(\\d+)").find(it.substringAfter(team, it))?.groupValues?.get(1)?.toIntOrNull() }

    /** Errors the server logged since line [from] (harness noise filtered). */
    protected fun serverErrorsSince(from: Int): List<String> = ServerLog.read().lineSequence().drop(from)
        .filter { !it.contains("authlib") && !it.contains("sessionserver.mojang.com") && !it.contains("SocketTimeoutException") }
        .filter { it.contains("/ERROR]") || it.contains("NotImplementedError") || it.contains(" threw") || (it.contains("/WARN]") && it.contains("Exception")) }
        .toList()

    protected fun logLineCount(): Int = ServerLog.read().lineSequence().count()

    /**
     * What every participant must have seen/gotten when a match ends on its own: the summary with the score line and
     * their own line, the reward block, no raw translation keys, and a way back to the lobby (kit in slot 8, lobby
     * sidebar, playing game mode, no stuck match).
     */
    protected fun checkEnding(suite: String, bots: List<Bot>, chatFrom: Map<Bot, Int>, lobbySidebarTitle: String?, backTimeoutMs: Long = 40_000) {
        bots.forEach { b -> b.waitUntil(backTimeoutMs) { b.hotbar()[8] != -1 && b.sidebarTitle() == lobbySidebarTitle } }
        for (b in bots) {
            val chat = b.chat.drop(chatFrom.getValue(b)).map { clean(it.text) }
            E2eReport.check(suite, "${b.name}: result screen (summary, score, own line) reached the player", "SystemChat") {
                assertThat(chat.any { it.contains("Match Summary") }).describedAs("no '== Match Summary ==' header in chat:\n${quote(chat, 40)}").isTrue()
                assertThat(chat.any { it.startsWith("Score") }).describedAs("no score line:\n${quote(chat, 40)}").isTrue()
                assertThat(chat.any { it.startsWith("You:") }).describedAs("no own-stats line:\n${quote(chat, 40)}").isTrue()
                assertThat(rawKeys(chat)).describedAs("raw i18n keys").isEmpty()
                quote(chat.filter { it.contains("Summary") || it.startsWith("Score") || it.startsWith("You:") || it.contains("Ended by") || it.contains("MVP") }, 12)
            }
            E2eReport.check(suite, "${b.name}: rewards were paid and announced", "SystemChat") {
                assertThat(chat.any { it.contains("Rewards") || it.contains("Reward:") })
                    .describedAs("no reward block/line after the match:\n${quote(chat, 40)}").isTrue()
                quote(chat.filter { it.contains("Reward") || it.contains("STAR") || it.contains("Level") || it.contains("Total") }, 12)
            }
            E2eReport.check(suite, "${b.name}: back in the lobby with kit, lobby sidebar and playable game mode", "inventory + sidebar + RCON") {
                assertThat(b.hotbar()[8] != -1).describedAs("lobby kit missing in slot 8: ${b.hotbar()}").isTrue()
                assertThat(b.sidebarTitle()).describedAs("still on the match sidebar: ${sidebar(b)}").isEqualTo(lobbySidebarTitle)
                val gt = gameTypeOf(b)
                assertThat(gt).describedAs("stuck in game mode $gt (3 = spectator)").isIn(0, 2)
                "hotbar=${b.hotbar()} sidebar='${b.sidebarTitle()}' gameType=$gt health=${b.health}"
            }
        }
        E2eReport.check(suite, "no match is left running after the end (no stuck state)", "RCON astera match list") {
            val left = matchLines()
            assertThat(left).describedAs("matches still listed: $left").isEmpty()
            "match list empty"
        }
    }
}
