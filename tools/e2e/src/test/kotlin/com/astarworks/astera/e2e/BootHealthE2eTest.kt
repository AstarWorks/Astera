package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * Cheap player-visible smoke checks that every past hand-found regression would have tripped (silent sounds, a dead HP
 * HUD, a missing BGM, a lobby with no NPCs). They run first-class in the batch and cost a few seconds:
 *  - the server boots with 0 ERROR / SEVERE / exception lines and every content directory loads with 0 failures;
 *  - the four lobby hubs (Play / Market / Social / Travel) exist and are visible to a client;
 *  - a fresh joiner receives the sidebar, the HP line and no raw i18n key;
 *  - the pushed resource pack really contains the lobby BGM and the cue events (the static "every referenced cue is
 *    shipped" rule lives in :tools:architecture-test SoundCueCoverageTest; this proves the built pack agrees).
 */
class BootHealthE2eTest : E2eBase() {

    private val suite = "BootHealth"

    /** Server log lines up to and including the first `Done (` line (the boot sequence). */
    private fun bootLines(): List<String> {
        val all = ServerLog.read().lines()
        val done = all.indexOfFirst { "Done (" in it }
        return if (done < 0) emptyList() else all.take(done + 1)
    }

    @Test
    fun `the server boots without ERROR lines and every content directory loads cleanly`() {
        val boot = bootLines()
        E2eReport.check(suite, "boot log has no ERROR / SEVERE / exception lines", "server log (up to 'Done (')") {
            assertThat(boot).describedAs("no 'Done (' line found in the server log; cannot isolate the boot sequence").isNotEmpty()
            val bad = boot.filter { Regex("\\bERROR\\]|\\bSEVERE\\]|/ERROR]|/SEVERE]|Exception").containsMatchIn(it) }
            assertThat(bad).describedAs("errors during boot:\n${quote(bad)}").isEmpty()
            "${boot.size} boot lines, 0 errors"
        }
        E2eReport.check(suite, "every ContentLoader directory reports 0 failed files", "server log 'Content load complete'") {
            val summaries = ServerLog.lines("Content load complete")
            assertThat(summaries).describedAs("no 'Content load complete' lines at all: content never loaded?").isNotEmpty()
            val failed = summaries.filter { line ->
                Regex("(\\d+) ok, (\\d+) failed").find(line)?.groupValues?.get(2)?.toIntOrNull()?.let { it > 0 } == true
            }
            assertThat(failed).describedAs("content directories with failed files:\n${quote(failed)}").isEmpty()
            "${summaries.size} content directories loaded, 0 failed"
        }
    }

    @Test
    fun `the lobby shows its four hub NPCs`() {
        val b = bot("HubWalker")
        op(b)
        b.waitUntil(10_000) { b.x != 0.0 || b.z != 0.0 }
        // (hub, name fragment of the villager's name tag, NPC spawn from content/lobby-npcs/*.yaml). The client only tracks
        // entities within view, so stand next to each one.
        val hubs = listOf(
            Triple("Play", "Play", Pair(0.5, 6.5)),
            Triple("Market", "Market", Pair(47.5, -3.5)),
            Triple("Social", "Social", Pair(4.5, -1.5)),
            Triple("Travel", "Travel", Pair(-3.5, -1.5)),
            // x / z are 2 blocks from each NPC's spawn (play 0.5/4.5, market 47.5/-5.5, social 4.5/-3.5, travel -3.5/-3.5)
        )
        for ((hub, fragment, at) in hubs) {
            rcon("tp ${b.name} ${at.first} 65.0 ${at.second}")
            val seen = b.waitUntil(6_000) { villagerNamed(b, fragment) }
            E2eReport.check(suite, "the $hub hub NPC exists in the lobby", "ClientboundAddEntity(VILLAGER) + name tag") {
                assertThat(seen).describedAs("no villager named '$fragment' seen near ($at); villagers=${
                    b.entityEvents.filter { it.type == "VILLAGER" }.map { it.id to b.entityTextsById[it.id] }
                }").isTrue()
                "villager '$fragment' visible"
            }
        }
    }

    private fun villagerNamed(b: Bot, fragment: String): Boolean =
        b.entityEvents.filter { it.added && it.type == "VILLAGER" }
            .any { ev -> b.entityTextsById[ev.id]?.any { fragment in it } == true }

    @Test
    fun `a fresh joiner receives the sidebar, the HP line, no raw i18n key, and the pack carries the lobby BGM`() {
        val b = bot("FreshJoin")
        b.waitUntil(10_000) { b.sidebarLines().isNotEmpty() && b.hpReadings().isNotEmpty() && b.packPushes.isNotEmpty() }
        Thread.sleep(500)
        E2eReport.check(suite, "the joiner receives a sidebar and an HP line (glyph bar or text)", "SetObjective/SetScore + SetActionBarText") {
            assertThat(b.sidebarLines()).describedAs("no sidebar lines received").isNotEmpty()
            assertThat(b.hpReadings()).describedAs("no parsable HP line; bars=${b.actionBars.takeLast(6).map { visible(it.text) }}").isNotEmpty()
            "sidebar=${b.sidebarLines().take(4)}; hp=${b.hpReadings().last()}"
        }
        E2eReport.check(suite, "no raw translation key on the sidebar, action bar or chat at join", "sidebar + action bar + chat") {
            val leaks = rawKeys(b.sidebarLines() + b.actionBars.map { visible(it.text) } + b.chatTexts())
            assertThat(leaks).describedAs("raw i18n keys shown to the player").isEmpty()
            "clean"
        }
        E2eReport.check(suite, "the pushed resource pack ships the lobby BGM and the combat cue events", "pack zip assets/astera/sounds.json") {
            val url = b.packPushes.firstOrNull()?.url
            assertThat(url).describedAs("no pack push to fetch").isNotNull()
            val json = fetchPackSoundsJson(url!!)
            if (json == null) {
                E2eReport.notes += "BootHealth: pack at $url not downloadable from the runner; sounds.json check skipped"
                "skipped (pack unreachable from the runner)"
            } else {
                val wanted = listOf("music/lobby-loop", "weapons/gun-shot", "weapons/sword-swing", "skill/wand/cast", "combat/hit")
                val missing = wanted.filter { "\"$it\"" !in json }
                assertThat(missing).describedAs("sound events missing from the shipped pack's sounds.json").isEmpty()
                "sounds.json carries $wanted"
            }
        }
    }

    /** `assets/astera/sounds.json` of the pack at [url], or null if the pack cannot be downloaded from here. */
    private fun fetchPackSoundsJson(url: String): String? = runCatching {
        val conn = URI(url).toURL().openConnection().apply { connectTimeout = 5_000; readTimeout = 20_000 }
        ZipInputStream(conn.getInputStream()).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == "assets/astera/sounds.json" }
                ?.let { zip.readBytes().toString(Charsets.UTF_8) }
                ?: ""
        }
    }.getOrNull()
}
