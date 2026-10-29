package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs

/**
 * The shipped gun shots draw a tracer line on a hit, a block contact and a miss (the end of the flight), and a line is volumetric:
 * its two crossed quads have non-zero extent on both axes perpendicular to from-to, so it reads edge-on too. Real loader, real
 * player, real `content/skills` (the shared `gun-tracer` callee), GatewayRecorder.
 */
class GunTracerScenarioTest : SkillEngineScenario() {
    private fun shipped(id: String): CompiledSkill {
        val c = SkillComposer(contentDir.resolve("skills")).composeAll()
        val composed = (c as? Result.Success)?.value ?: error("compose failed: $c")
        val ids = composed.keys.toSet()
        val defs = composed.mapValues { (_, t) ->
            when (val d = decodeTree(id, t.tree, ids)) {
                is Result.Success -> d.value
                is Result.Failure -> error("decode: ${d.error}")
            }
        }
        return when (val r = compileAgainst(defs.getValue(id), defs)) {
            is Result.Success -> r.value
            is Result.Failure -> error("compile: ${r.error}")
        }
    }

    private fun victim(z: Double) =
        HitCandidate(UUID(0L, 10L), Vec3(0.0, 0.0, z), 0.0, HitBox(Vec3(-0.4, 0.0, z - 0.4), Vec3(0.4, 1.8, z + 0.4)), 1.62)

    private fun vec(s: String): Vec3 = s.split(",").let { Vec3(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }

    private fun tracers(victims: List<HitCandidate>): List<String> {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = victims
        val calls = play(shipped("rifle-shot"), seed = 1L, events = emptyList(), ticks = 60, rec = rec)
        return calls.filter { it.verb == "spawn" && it.payload == "kind=ribbon" && it.detail.contains("quads=") }.map { it.detail }
    }

    private fun assertVolumetric(detail: String) {
        val from = vec(Regex("""from=(-?[0-9.]+,-?[0-9.]+,-?[0-9.]+)""").find(detail)!!.groupValues[1])
        val to = vec(Regex("""to=(-?[0-9.]+,-?[0-9.]+,-?[0-9.]+)""").find(detail)!!.groupValues[1])
        val dir = (to - from).normalize()
        val across = Regex("""\[[^|]+\|(-?[0-9.]+,-?[0-9.]+,-?[0-9.]+)\]""").findAll(detail).map { vec(it.groupValues[1]) }.toList()
        assertThat(across).hasSize(2)
        for (a in across) {
            assertThat(a.length()).isGreaterThan(0.01)
            assertThat(abs(a.dot(dir))).isLessThan(1e-3)
        }
        // the two quads span two different directions across the line: a flat plane seen edge-on would have one
        assertThat(abs(across[0].normalize().dot(across[1].normalize()))).isLessThan(1e-3)
    }

    @Test
    fun hitDrawsAVolumetricLineEndingOnTheVictim() {
        val lines = tracers(listOf(victim(12.0)))
        assertThat(lines).hasSize(1)
        assertVolumetric(lines.single())
    }

    @Test
    fun missDrawsAVolumetricLineToTheEndOfTheFlight() {
        val lines = tracers(emptyList())
        assertThat(lines).hasSize(1)
        assertVolumetric(lines.single())
        assertThat(vec(Regex("""to=(-?[0-9.]+,-?[0-9.]+,-?[0-9.]+)""").find(lines.single())!!.groupValues[1]).z).describedAs(lines.single()).isGreaterThan(30.0)
    }
}
