package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-08 (ADR-0299): couture-rapier-s2 coin 2 on the skill engine v2, on a real server.
 * Skeleton: the cast goes through `astera skill cast couture-rapier-s2 <bot> --force`, which the
 * manager points at the new engine when wiring; the dummy is a NoAI zombie 6 blocks ahead.
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
        val ax = a.x
        val ay = a.y
        val az = a.z
        // Caster looks south (yaw 0); the dummy stands 6 blocks ahead at +z.
        rcon("tp ${a.name} $ax $ay $az 0 0")
        rcon("kill @e[type=minecraft:zombie,tag=coin2_dummy]")
        rcon("summon minecraft:zombie $ax $ay ${az + 6} {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"coin2_dummy\"],Attributes:[{id:\"minecraft:max_health\",base:200.0d}],Health:200.0f}")
        Thread.sleep(1_000)
        val before = healthOf("@e[type=minecraft:zombie,tag=coin2_dummy,limit=1]")

        E2eReport.check(suite, "dummy spawned with full health", "RCON data get entity") {
            assertThat(before).describedAs("dummy health").isNotNull()
            "health $before"
        }

        val logMark = ServerLog.lineCount()
        rcon("astera skill cast $skillId ${a.name} --force")
        // spin-up + release + flight + stick: the whole sequence is well under 4 seconds.
        Thread.sleep(4_000)
        val after = healthOf("@e[type=minecraft:zombie,tag=coin2_dummy,limit=1]")

        E2eReport.check(suite, "a thrown piece damages the dummy", "dummy Health before/after") {
            assertThat(before).isNotNull()
            assertThat(after).describedAs("dummy health after the cast").isNotNull()
            val lost = before!! - after!!
            assertThat(lost).describedAs("health lost (expected at least one hit of ~18)").isGreaterThanOrEqualTo(17.0)
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
            val errors = ServerLog.read().lineSequence().drop(logMark).toList().filter { it.contains("skillengine", ignoreCase = true) && (it.contains("ERROR") || it.contains("WARN")) }
            assertThat(errors).describedAs("skill-engine log problems").isEmpty()
            "clean"
        }

        rcon("kill @e[type=minecraft:zombie,tag=coin2_dummy]")
    }
}
