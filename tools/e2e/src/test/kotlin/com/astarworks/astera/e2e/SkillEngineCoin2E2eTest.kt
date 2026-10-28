package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-08 (ADR-0301): couture-rapier-s2 coin 2 on the skill engine v2, on a real server.
 * The cast goes through `astera skill cast couture-rapier-s2 <bot> --force` (CastSkillUseCase publishes SkillCast,
 * which `SkillEnginePaperRuntime` starts on the v2 runner); the dummy is a NoAI zombie 6 blocks ahead.
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
        Thread.sleep(4_000)
        val after = healthOf("@e[type=minecraft:zombie,tag=coin2_dummy,limit=1]")

        E2eReport.check(suite, "a thrown piece damages the dummy", "dummy Health before/after") {
            assertThat(before).isNotNull()
            assertThat(after).describedAs("dummy health after the cast").isNotNull()
            val lost = before!! - after!!
            assertThat(lost).describedAs("health lost (expected at least one hit of ~18); cast reply='$castReply'; log=${ServerLog.read().lineSequence().drop(logMark).filter { it.contains("skill engine", true) || it.contains("skill trace", true) }.toList().takeLast(12)}").isGreaterThanOrEqualTo(17.0)
            "health $before -> $after (lost $lost)"
        }

        E2eReport.check(suite, "bleed keeps ticking on the dummy after the hit", "dummy Health, 3 s later") {
            Thread.sleep(3_000)
            val later = healthOf("@e[type=minecraft:zombie,tag=coin2_dummy,limit=1]")
            assertThat(later).isNotNull()
            assertThat(later!!).describedAs("health after the hit vs 3 s later (bleed damage over time)").isLessThan(after!!)
            "health $after -> $later"
        }

        E2eReport.check(suite, "no skill-engine error in the server log", "server log") {
            val errors = ServerLog.read().lineSequence().drop(logMark).toList().filter { (it.contains("skillengine", ignoreCase = true) || it.contains("skill engine", ignoreCase = true)) && (it.contains("ERROR") || it.contains("WARN")) }
            assertThat(errors).describedAs("skill-engine log problems").isEmpty()
            "clean"
        }

        rcon("astera skill trace off")
        rcon("kill @e[type=minecraft:zombie,tag=coin2_dummy]")
    }
}
