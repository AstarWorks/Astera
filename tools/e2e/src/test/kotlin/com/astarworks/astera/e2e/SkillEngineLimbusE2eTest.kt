package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Skill engine v3 (ADR-0303), the shipped Limbus skills (bastion-greatsword, couture-rapier) cast by a bot on a real Paper server.
 * Every case: `astera skill cast <id> <bot> --force` at a NoAI dummy, then
 *  1. no skill-load or runtime error/warning in the server log for the cast;
 *  2. the live display count (item/text/block displays near the bot, sampled while the cast runs) peaks inside the range the oracle dump gives:
 *     at least 1, at most the number of non-frame spawns in the dump (a display is never alive twice, so the peak cannot exceed it);
 *  3. the dummy loses health when the dump holds `hit` rows (a defence skill has none and is not asked to);
 *  4. one second after the end tick no display of the cast is left alive (founder remark 22): the count is back to the pre-cast baseline.
 *
 * The oracle numbers are those of `tools/game-sim/build/skill-expand/shipped-<id>_skill.txt` (ShippedV3ContentScenarioTest): [Oracle.endTick]
 * is the longer of the last dump row and the cast state's duration, [Oracle.spawns] the non-frame spawn rows, [Oracle.hits] the `hit` rows.
 * Particle bursts are not observable through RCON (no entity, no log line), so their count stays a game-sim assertion; here the cast
 * must only finish without a server error.
 */
class SkillEngineLimbusE2eTest : E2eBase() {

    private val suite = "SkillEngine Limbus"

    private data class Oracle(val id: String, val endTick: Int, val spawns: Int, val hits: Int)

    private fun clean(s: String): String = s.replace(Regex("§."), "").trim()

    private fun healthOf(selector: String): Double? =
        Regex("(-?\\d+(\\.\\d+)?)").find(clean(rcon("data get entity $selector Health")).substringAfter("has the following entity data:"))
            ?.groupValues?.get(1)?.toDoubleOrNull()

    private val displayTypes = listOf("minecraft:item_display", "minecraft:text_display", "minecraft:block_display")

    private fun displaysNear(player: String): Int = displayTypes.sumOf { type ->
        Regex("count: (\\d+)").find(clean(rcon("execute at $player if entity @e[type=$type,distance=..64]")))?.groupValues?.get(1)?.toInt() ?: 0
    }

    private fun runCase(o: Oracle) {
        val a = bot("Limbus")
        op(a)
        Thread.sleep(500)
        val ax = 0.5
        val ay = -60
        val az = 0.5
        rcon("kill @e[type=minecraft:zombie,tag=limbus_dummy]")
        rcon("execute in minecraft:overworld run tp ${a.name} $ax $ay $az 0 0")
        Thread.sleep(800)
        rcon("execute in minecraft:overworld run summon minecraft:zombie $ax $ay ${az + 3} {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"limbus_dummy\"],attributes:[{id:\"minecraft:max_health\",base:1000}],Health:1000f,active_effects:[{id:\"minecraft:fire_resistance\",duration:100000,show_particles:0b}]}")
        Thread.sleep(1_000)
        val dummy = "@e[type=minecraft:zombie,tag=limbus_dummy,limit=1]"
        val healthBefore = healthOf(dummy)
        val baseline = displaysNear(a.name)

        val logMark = ServerLog.lineCount()
        val reply = clean(rcon("astera skill cast ${o.id} ${a.name} --force"))
        var peak = 0
        val castMs = o.endTick * 50L
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < castMs + 500) {
            peak = maxOf(peak, displaysNear(a.name) - baseline)
            Thread.sleep(100)
        }
        // end tick + 1 s (20 ticks) + a margin for a server that is slower than 20 TPS
        Thread.sleep(1_000 + 1_500)
        val left = displaysNear(a.name) - baseline
        val healthAfter = healthOf(dummy)

        E2eReport.check(suite, "${o.id}: no skill-engine load or runtime error in the server log", "server log") {
            val errors = ServerLog.read().lineSequence().drop(logMark).toList().filter {
                (it.contains("skillengine", true) || it.contains("skill engine", true) || it.contains(o.id)) &&
                    (it.contains("ERROR") || it.contains("WARN") || it.contains("Exception"))
            }
            assertThat(errors).describedAs("skill-engine log problems; cast reply='$reply'").isEmpty()
            "clean"
        }

        E2eReport.check(suite, "${o.id}: live displays peak inside the oracle range", "RCON execute if entity (displays), sampled every 100 ms") {
            assertThat(peak).describedAs("peak displays during the cast (oracle: 1..${o.spawns}); cast reply='$reply'").isBetween(1, o.spawns)
            "peak $peak of at most ${o.spawns}"
        }

        if (o.hits > 0) {
            E2eReport.check(suite, "${o.id}: the dummy is hit (oracle has ${o.hits} hit rows)", "dummy Health before/after") {
                assertThat(healthBefore).isNotNull()
                assertThat(healthAfter).describedAs("dummy health after the cast").isNotNull()
                assertThat(healthBefore!! - healthAfter!!).describedAs("health lost").isGreaterThan(0.0)
                "health $healthBefore -> $healthAfter"
            }
        }

        E2eReport.check(suite, "${o.id}: nothing left alive 1 s after the end tick (${o.endTick})", "RCON execute if entity (displays)") {
            assertThat(left).describedAs("displays still alive after end tick ${o.endTick} + 1 s").isEqualTo(0)
            "left $left"
        }

        rcon("kill @e[type=minecraft:zombie,tag=limbus_dummy]")
    }

    @Test fun bastionGreatswordS1() = runCase(Oracle("bastion-greatsword-s1", 58, 23, 8))
    @Test fun bastionGreatswordS2() = runCase(Oracle("bastion-greatsword-s2", 80, 34, 12))
    @Test fun bastionGreatswordDef() = runCase(Oracle("bastion-greatsword-def", 162, 6, 0))
    @Test fun bastionGreatswordEgo() = runCase(Oracle("bastion-greatsword-ego", 70, 93, 7))
    @Test fun coutureRapierDef() = runCase(Oracle("couture-rapier-def", 46, 60, 0))
    @Test fun coutureRapierS1() = runCase(Oracle("couture-rapier-s1", 37, 48, 5))
    @Test fun coutureRapierS3() = runCase(Oracle("couture-rapier-s3", 138, 191, 10))
    @Test fun coutureRapierEgo() = runCase(Oracle("couture-rapier-ego", 73, 114, 5))

    // couture-rapier-s2 has no shipped oracle dump yet (replayed only by ShippedV3ContentScenarioTest); its three reel threads are line ribbons (text displays, spawnLine) and count in the display peak; the bounds are the
    // engine's own max_live_displays (64) and the content comment "four hits"; the end tick is the sticking window with its reel.
    @Test fun coutureRapierS2() = runCase(Oracle("couture-rapier-s2", 200, 64, 4))
}
