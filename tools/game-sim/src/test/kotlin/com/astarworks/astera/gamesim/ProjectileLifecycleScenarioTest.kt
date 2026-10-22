package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * The shared `ProjectileImpactResolver` runs exactly once per projectile, whichever way
 * it ends: a shot into empty air expires (`expire` then `miss`), a shot into the ground
 * hits a block (`hit_block` then `miss`).
 */
class ProjectileLifecycleScenarioTest {

    @Test
    fun `a missed projectile expires once and plays the miss cue once`() {
        val trace = scenario("projectile-expires") {
            val shooter = player("shooter")
            shooter.holds("pistol")
            shooter.rightClick()
            advance(80)
            assertThat(sim.projectiles.inFlight).isZero()
        }
        val kinds = projectileEventKinds(trace)
        assertThat(kinds).containsExactly("spawn", "expire")
        // pistol-shot's `cast` phase plays one report; `miss` / `expire` are empty.
        assertThat(soundCount(trace, "entity.firework_rocket.blast")).isEqualTo(1)
    }

    @Test
    fun `a projectile fired at the ground hits a block once`() {
        val trace = scenario("projectile-hits-ground") {
            val shooter = player("shooter")
            shooter.holds("pistol")
            shooter.lookAt(Vec3(0.0, groundY, 6.0)) // down at the floor, 6 blocks ahead
            shooter.rightClick()
            advance(40)
            assertThat(sim.projectiles.inFlight).isZero()
        }
        assertThat(projectileEventKinds(trace)).containsExactly("spawn", "hit_block")
        assertThat(soundCount(trace, "entity.firework_rocket.blast")).isEqualTo(1)
    }

    private fun projectileEventKinds(trace: java.nio.file.Path): List<String> =
        ticks(trace).flatMap { it["projectileEvents"]!!.jsonArray }
            .map { it.jsonObject["kind"]!!.jsonPrimitive.content }

    private fun soundCount(trace: java.nio.file.Path, key: String): Int =
        ticks(trace).flatMap { it["sounds"]!!.jsonArray }
            .count { it.jsonObject["key"]!!.jsonPrimitive.content == key }

    private fun ticks(trace: java.nio.file.Path) =
        Json.parseToJsonElement(Files.readString(trace)).jsonObject["ticks"]!!.jsonArray.map { it.jsonObject }
}
