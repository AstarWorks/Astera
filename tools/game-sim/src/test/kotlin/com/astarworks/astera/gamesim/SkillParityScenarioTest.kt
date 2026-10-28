package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.math.min

/**
 * SKL-14: the ported projectile skills keep the v1 numbers. `skill-parity/expected-v1.json` is derived from the v1
 * engine's params (launch_speed in blocks per tick, hit_radius, muzzle_forward, aim_distance, trail_spacing and the
 * defaults of `SkillParams`). Each projectile skill is cast at one dummy straight ahead and must
 *  - first hurt it at the tick a v1 projectile (parallel to the look ray, sphere hit_radius, swept) would reach it, and
 *  - replay its trail once per `trail_spacing` blocks of the segment covered per tick, per trail particle.
 */
class SkillParityScenarioTest {

    private class Expected(val id: String, val speed: Double, val life: Int, val radius: Double, val muzzle: Double, val spacing: Double, val trailParts: Int)

    private fun expected(): List<Expected> {
        val text = SkillParityScenarioTest::class.java.getResource("/skill-parity/expected-v1.json")!!.readText()
        val root = Json.parseToJsonElement(text).jsonObject
        return root["projectiles"]!!.jsonArray.map { e ->
            val o: JsonObject = e.jsonObject
            require(o["aimDistance"]!!.jsonPrimitive.double == 0.0) { "${o["id"]}: aim_distance > 0 skills are not covered by the parallel-flight pin" }
            Expected(
                o["id"]!!.jsonPrimitive.content, o["launchSpeed"]!!.jsonPrimitive.double, o["maxLifetimeTicks"]!!.jsonPrimitive.int,
                o["hitRadius"]!!.jsonPrimitive.double, o["muzzleForward"]!!.jsonPrimitive.double, o["trailSpacing"]!!.jsonPrimitive.double,
                (o["trail"] as JsonArray).size,
            )
        }
    }

    @Test
    fun `projectile skills hit at the v1 flight time`() {
        val problems = ArrayList<String>()
        for (x in expected()) {
            val dist = min(12.0, x.speed * x.life * 0.6)
            val sim = CastSim(mapOf(simVictim(1) to ahead(Vec3.ZERO, 0.0, dist)))
            sim.cast(x.id, x.life + 5)
            val first = sim.dealt.minOfOrNull { it.tick }
            // bolt centre after k steps: muzzle + k * speed; contact when centre + radius reaches the box face (half width 0.8)
            val k = ceil((dist - 0.8 - x.radius - x.muzzle) / x.speed).toInt()
            val hasDamage = Coin2CastWiring.collaborators().skillsDir.resolve(x.id).let { d ->
                java.nio.file.Files.walk(d).use { st -> st.anyMatch { it.toString().endsWith(".yaml") && Regex("""op:\s*damage\b""").containsMatchIn(java.nio.file.Files.readString(it)) } }
            }
            if (first == null && !hasDamage) continue // utility throwables (smoke, flash, cloud) hurt nobody by design
            if (first == null) problems += "${x.id}: never hurt a dummy ${"%.1f".format(dist)} blocks ahead (v1 hit at about tick $k)"
            else if (first < k - 1 || first > k + 1) problems += "${x.id}: first damage at tick $first, v1 flight time says $k (speed ${x.speed}/tick, radius ${x.radius}, dist $dist)"
        }
        assertThat(problems).describedAs("projectile parity").isEmpty()
    }

    @Test
    fun `projectile trails replay at the v1 spacing`() {
        val problems = ArrayList<String>()
        for (x in expected()) {
            if (x.trailParts == 0) continue
            fun bursts(ticks: Int): Int {
                var n = 0
                val sink = object : ISkillParticleGateway { override fun emit(burst: ParticleBurst) { n++ } }
                CastSim(emptyMap(), particleGateway = sink).cast(x.id, ticks)
                return n
            }
            val perTick = bursts(5) - bursts(4)
            val steps = if (x.speed > x.spacing) ceil(x.speed / x.spacing).toInt() else 1
            if (perTick < x.trailParts * steps || perTick > x.trailParts * (steps + 1)) problems += "${x.id}: $perTick trail plays on tick 4, v1 replays ${x.trailParts} parts x $steps (+1 step float slack) (speed ${x.speed}, spacing ${x.spacing})"
        }
        assertThat(problems).describedAs("trail parity").isEmpty()
    }
}
