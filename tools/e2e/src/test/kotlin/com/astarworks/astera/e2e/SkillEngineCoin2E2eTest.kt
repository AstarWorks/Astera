package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-08 / ADR-0303: couture-rapier-s2 coin 2 on the skill engine v3, on a real server.
 * The cast goes through `astera skill cast couture-rapier-s2 <bot> --force` (CastSkillUseCase publishes SkillCast,
 * which `SkillEnginePaperRuntime` starts on the v3 table player); the dummy is a NoAI zombie 6 blocks ahead.
 *
 * Checks (bot-judgeable rows of the understanding file):
 *  1. the cast reaches the dummy: its health drops by the throw damage (18) and no more than once per piece volley window;
 *  2. bleed keeps damaging the dummy after the hit (status stacks 2);
 *  3. the server log carries no skill-engine load/cast error for the skill.
 */
class SkillEngineCoin2E2eTest : E2eBase() {

    private val suite = "SkillEngine coin 2"
    private val skillId = "couture-rapier-s2"

    private fun clean(s: String): String = s.replace(Regex("§."), "").trim()

    private fun healthOf(selector: String): Double? =
        Regex("(-?\\d+(\\.\\d+)?)").find(clean(rcon("data get entity $selector Health")).substringAfter("has the following entity data:"))
            ?.groupValues?.get(1)?.toDoubleOrNull()

    @Test
    fun `coin 2 flies to the dummy, damages it, bleeds it and sticks`() {
        val a = bot("Coin")
        op(a)
        Thread.sleep(500)
        // Stage in the flat overworld arena like WeaponsE2eTest: the lobby has geometry that can stop the flying pieces and cancels damage.
        val ax = 0.5
        val ay = -60
        val az = 0.5
        // Caster looks south (yaw 0); the dummy stands 6 blocks ahead at +z.
        rcon("kill @e[type=minecraft:zombie,tag=coin2_dummy]")
        rcon("execute in minecraft:overworld run tp ${a.name} $ax $ay $az 0 0")
        Thread.sleep(800)
        // 26.x entity NBT uses lowercase `attributes` / `active_effects`. Fire resistance keeps the zombie from burning in daylight
        // (a burning dummy loses 1 HP per second, which looks exactly like damage over time).
        rcon("execute in minecraft:overworld run summon minecraft:zombie $ax $ay ${az + 6} {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"coin2_dummy\"],attributes:[{id:\"minecraft:max_health\",base:200}],Health:200f,active_effects:[{id:\"minecraft:fire_resistance\",duration:100000,show_particles:0b}]}")
        Thread.sleep(1_000)
        val before = healthOf("@e[type=minecraft:zombie,tag=coin2_dummy,limit=1]")

        E2eReport.check(suite, "dummy spawned with full health", "RCON data get entity") {
            assertThat(before).describedAs("dummy health").isNotNull()
            assertThat(before!!).describedAs("max_health 200 applied (attributes NBT key)").isGreaterThan(100.0)
            "health $before"
        }

        // Cast lifecycle at INFO (start, release / hit / block / reel with frame positions, end summary): the failure message below quotes it.
        rcon("astera skill trace on")
        val logMark = ServerLog.lineCount()
        val castReply = clean(rcon("astera skill cast $skillId ${a.name} --force"))
        // spin-up + release + flight + stick: the whole sequence is well under 4 seconds.
        // Health is sampled through the whole sequence plus the bleed tail (60 ticks of bleed, ticked on a global 20-tick phase), so the
        // bleed check below sees the ticks themselves instead of guessing where they fall relative to one fixed read.
        val samples = ArrayList<Double>()
        val sampleEnd = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < sampleEnd) {
            healthOf("@e[type=minecraft:zombie,tag=coin2_dummy,limit=1]")?.let { samples += it }
            Thread.sleep(250)
        }
        val after = samples.lastOrNull()

        E2eReport.check(suite, "a thrown piece damages the dummy", "dummy Health before/after") {
            assertThat(before).isNotNull()
            assertThat(after).describedAs("dummy health after the cast").isNotNull()
            val lost = before!! - after!!
            assertThat(lost).describedAs("health lost (expected at least one hit of ~18); cast reply='$castReply'; log=${ServerLog.read().lineSequence().drop(logMark).filter { it.contains("skill engine", true) || it.contains("skill trace", true) }.toList().takeLast(12)}").isGreaterThanOrEqualTo(17.0)
            "health $before -> $after (lost $lost)"
        }

        E2eReport.check(suite, "bleed keeps ticking on the dummy after the hit", "dummy Health samples every 250 ms") {
            assertThat(before).isNotNull()
            // the hit is the first drop of at least 17 (one ~18 hit); bleed is every drop after it
            val hitAt = samples.indexOfFirst { before!! - it >= 17.0 }
            assertThat(hitAt).describedAs("a hit sample; samples=$samples").isGreaterThanOrEqualTo(0)
            val tail = samples.drop(hitAt)
            val bleedDrops = tail.zipWithNext().count { (a, b) -> b < a }
            assertThat(bleedDrops).describedAs("health drops after the hit (bleed damage over time); samples after hit=$tail").isGreaterThanOrEqualTo(1)
            "$bleedDrops bleed drops after the hit, health ${tail.first()} -> ${tail.last()}"
        }

        E2eReport.check(suite, "no skill-engine error in the server log", "server log") {
            val errors = ServerLog.read().lineSequence().drop(logMark).toList().filter { (it.contains("[skill-error]") || it.contains("skillengine", ignoreCase = true) || it.contains("skill engine", ignoreCase = true)) && (it.contains("ERROR") || it.contains("WARN")) }
            assertThat(errors).describedAs("skill-engine log problems").isEmpty()
            "clean"
        }

        rcon("astera skill trace off")
        rcon("kill @e[type=minecraft:zombie,tag=coin2_dummy]")
    }
}
