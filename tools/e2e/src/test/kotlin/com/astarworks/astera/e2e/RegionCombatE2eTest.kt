package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Real Paper/Folia entity lifecycle + combat wiring; the sim cannot exercise the spatial index. */
class RegionCombatE2eTest : E2eBase() {
    @Test
    fun `melee finds newly spawned and teleported mobs without a world scan`() {
        val caster = bot("GridCaster")
        op(caster)
        val x = caster.x
        val y = caster.y
        val z = caster.z
        val tag = "astera_t8b_grid_probe"
        fun health(): Double {
            val reply = rcon("data get entity @e[tag=$tag,limit=1] Health")
            return Regex("(-?[0-9]+(?:\\.[0-9]+)?)[fFdD]?$").find(reply.trim())?.groupValues?.get(1)?.toDouble()
                ?: error("Cannot read probe health: $reply")
        }
        fun cast() { caster.command("astera skill cast sword-slash --force") }
        try {
            rcon("tp ${caster.name} $x $y $z 0 0")
            rcon("summon minecraft:iron_golem $x $y ${z + 2.0} {NoAI:1b,Silent:1b,Tags:[\"$tag\"]}")
            caster.waitUntil(4_000) { runCatching { health() == 100.0 }.getOrDefault(false) }
            cast()
            caster.waitUntil(4_000) { health() < 100.0 }
            val afterFirst = health()
            E2eReport.check("Region combat", "newly spawned target is indexed", "entity health") {
                assertThat(afterFirst).isIn(92.0, 84.0)
                "golem HP 100 -> $afterFirst"
            }

            // Keep the old snapshot near the caster if teleport maintenance breaks.
            rcon("tp @e[tag=$tag,limit=1] ${x + 48.0} $y ${z + 2.0}")
            Thread.sleep(1_100) // i-frames must not hide a stale-grid hit
            cast()
            Thread.sleep(500)
            E2eReport.check("Region combat", "teleported target leaves the nearby buckets", "entity health") {
                assertThat(health()).isEqualTo(afterFirst)
                "far target HP remains $afterFirst"
            }

            rcon("tp @e[tag=$tag,limit=1] $x $y ${z + 2.0}")
            Thread.sleep(1_100)
            cast()
            caster.waitUntil(4_000) { health() < afterFirst }
            E2eReport.check("Region combat", "returning target is found", "entity health") {
                assertThat(health()).isLessThan(afterFirst)
                "returning golem took the next slash"
            }
            rcon("kill @e[tag=$tag]")
            caster.waitUntil(4_000) { rcon("execute if entity @e[tag=$tag,limit=1]").contains("failed", ignoreCase = true) }
            cast() // removed entities must not remain actionable candidates

        } finally {
            rcon("kill @e[tag=$tag]")
        }
    }
}
