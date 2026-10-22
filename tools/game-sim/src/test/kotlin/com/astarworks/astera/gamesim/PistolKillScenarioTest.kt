package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** Scenario 1: a pistol shot kills a player -> `PlayerKilled` -> lobby respawn. */
class PistolKillScenarioTest {

    @Test
    fun `pistol shots kill a player and the victim returns to the lobby`() {
        val trace = scenario("pistol-kills-player") {
            val shooter = player("shooter", team = "red", at = Vec3(0.0, groundY, 0.0))
            val victim = player("victim", team = "blue", at = Vec3(0.0, groundY, 10.0))
            shooter.holds("example-pistol")
            shooter.faces(victim)

            // example-blast has a 40-tick skill cooldown, so keep clicking until the kill lands.
            repeat(20) {
                if (eventsOf<PlayerKilled>().isEmpty()) {
                    shooter.rightClick()
                    advance(10)
                }
            }

            assertThat(eventsOf<DamageDealt>()).isNotEmpty()
            val kill = eventsOf<PlayerKilled>().single()
            assertThat(kill.victim).isEqualTo(victim.id)
            assertThat(kill.killer).isEqualTo(shooter.id)
            assertThat(sim.projectiles.inFlight).isLessThanOrEqualTo(1)

            // Out-of-match death path: the victim is returned to the lobby spawn at full health.
            assertThat(hp(victim)).isEqualTo(20.0)
            assertThat(victim.position.z).isEqualTo(0.0)

            // The cast left its cues in the trace recorder.
            assertThat(sim.recorder.particleKeys()).contains("example-burst")
            assertThat(sim.recorder.soundKeys()).contains("entity.blaze.shoot")
        }

        // JSON trace export (schema documented in tools/game-sim/README.md).
        val json = Json.parseToJsonElement(Files.readString(trace)).jsonObject
        assertThat(json["schema"]?.jsonPrimitive?.content).isEqualTo(TraceRecorder.SCHEMA)
        val ticks = json["ticks"]!!.jsonArray.map { it.jsonObject }
        assertThat(ticks.first()["entities"]!!.jsonArray).hasSize(2) // starting frame
        assertThat(ticks.any { it["projectiles"]!!.jsonArray.isNotEmpty() }).isTrue()
        assertThat(ticks.flatMap { it["deaths"]!!.jsonArray }).hasSize(1)
        assertThat(ticks.flatMap { it["damage"]!!.jsonArray }).isNotEmpty()
    }
}
