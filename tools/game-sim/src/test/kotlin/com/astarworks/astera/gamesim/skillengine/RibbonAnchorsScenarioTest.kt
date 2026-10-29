package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A line ribbon takes event-bound ends (`caster.eye`, `victim.center`, chain `from.center`) beside frame instances: both ends are read live
 * every tick; a name the row's event does not bind is a load error. Real loader, real player, GatewayRecorder.
 */
class RibbonAnchorsScenarioTest : SkillEngineScenario() {
    private fun dummy(i: Long, z: Double) =
        HitCandidate(UUID(0L, 10L + i), Vec3(0.0, 0.0, z), 0.0, HitBox(Vec3(-0.3, 0.0, z - 0.3), Vec3(0.3, 1.8, z + 0.3)), 1.62)

    private fun skill(arc: String, handler: String = "- {id: jump, op: use, use: \"fragment:arc\", chain: {hops: 3, every: 3, pick: {kind: enemies, around: from, radius: 4.5, nearest: 1, exclude: chain}}}", states: String = "do: []", tail: String = ""): String = """
        apiVersion: astera/v3
        kind: Skill
        id: ribbon-anchors
        fragments:
          arc:
            do:
              - {id: dmg, op: damage, target: victim, damage: {fire: 5}}
              - $arc
        machine:
          start: cast
          $tail
          world:
            - id: bolt
              op: hit
              at: 0
              anchor: caster
              shape: {sphere: 30.0}
              targets: enemies
              max_count: 1
              ignore_invulnerability: false
              damage: {fire: 5}
              on:
                hit:
                  $handler
          states:
            cast:
              for: 30
              $states
    """.trimIndent()

    private fun load(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error.message
    }

    private fun ends(detail: String, key: String): Vec3 {
        val m = Regex("""$key=(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+)""").find(detail) ?: error("no $key in '$detail'")
        return Vec3(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble())
    }

    @Test
    fun chainHopDrawsALineFromThePreviousVictimToTheNewOne() {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = (1L..4L).map { dummy(it, it * 3.0) }
        val calls = play(load(skill("{id: arc, op: ribbon, from: from.center, to: victim.center, thickness: 0.14, color: \"#b8e0ff\", at: 0, for: 3, detach: true}", tail = "tail: 12")), seed = 1L, events = emptyList(), ticks = 30, rec = rec)
        val lines = calls.filter { it.verb == "spawn" && it.payload == "kind=ribbon" }
        assertThat(lines.map { it.tick }).containsExactly(3, 6, 9)
        for ((n, line) in lines.withIndex()) {
            val k = n + 1
            // hop k goes from victim k (z = 3k) to victim k+1 (z = 3k+3), at the centre of the hitbox (y = 0.81)
            assertThat(ends(line.detail, "from").z).isEqualTo(3.0 * k)
            assertThat(ends(line.detail, "to").z).isEqualTo(3.0 * k + 3.0)
            assertThat(ends(line.detail, "to").y).isEqualTo(0.81)
        }
        // both ends are re-read while the line lives
        assertThat(calls.count { it.verb == "line" }).isGreaterThan(0)
    }

    @Test
    fun casterAndVictimEndsOutsideAChain() {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = listOf(dummy(1, 4.0))
        val handler = "- {id: beam, op: ribbon, from: caster.eye, to: victim.center, thickness: 0.1, color: \"#ffffff\", at: 0, for: 4, detach: true}"
        val calls = play(load(skill("{id: nothing, op: sound, sound: \"astera:x\", anchor: caster}", handler, tail = "tail: 12")), seed = 1L, events = emptyList(), ticks = 10, rec = rec)
        val line = calls.single { it.verb == "spawn" && it.payload == "kind=ribbon" }
        assertThat(ends(line.detail, "from").y).isEqualTo(1.62)
        assertThat(ends(line.detail, "to").z).isEqualTo(4.0)
    }

    @Test
    fun unboundNamesAreLoadErrors() {
        // `from` is the previous victim of a chain hop: a plain hit handler does not bind it
        val fromInPlainHandler = skill("{id: nothing, op: sound, sound: \"astera:x\", anchor: caster}", "- {id: beam, op: ribbon, from: from.center, to: victim.center, thickness: 0.1, color: \"#ffffff\", at: 0, for: 4}")
        assertThat(failure(fromInPlainHandler)).contains("'from.center' is only bound inside a fragment called with chain:")
        // `victim` is bound by a hit, not by a state's own rows
        val victimInState = skill("{id: nothing, op: sound, sound: \"astera:x\", anchor: caster}", states = "do:\n                - {id: beam, op: ribbon, from: caster.eye, to: victim.center, thickness: 0.1, color: \"#ffffff\", at: 0, for: 4}")
        assertThat(failure(victimInState)).contains("'victim.center' is only bound inside a hit handler")
        // a name that is nothing at all
        val unknown = skill("{id: arc, op: ribbon, from: from.center, to: nowhere, thickness: 0.1, color: \"#ffffff\", at: 0, for: 3}")
        assertThat(failure(unknown)).contains("nowhere")
    }
}
