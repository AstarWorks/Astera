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
 * Founder 10/29: Q1 = B (`max_count` optional on an area target and on a `hit`; omitted or `all` = unlimited, an integer keeps the
 * nearest N) and Q4 = A (`damage` optional on a `hit`; a trigger-only hit loads and its handler `damage` deals it). Real loader, real player.
 */
class MaxCountOptionalScenarioTest : SkillEngineScenario() {
    private fun load(yaml: String): CompiledSkill = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun foe(i: Long, z: Double) =
        HitCandidate(UUID(0L, 10L + i), Vec3(0.0, 0.0, z), 0.0, HitBox(Vec3(-0.3, 0.0, z - 0.3), Vec3(0.3, 1.8, z + 0.3)), 1.62)

    private val foes = listOf(foe(1, 1.0), foe(2, 2.0), foe(3, 3.0), foe(4, 4.0))

    private fun played(skill: CompiledSkill, verb: String): List<RecordedCall> {
        val rec = GatewayRecorder()
        rec.victims = foes
        return play(skill, seed = 1L, events = emptyList(), ticks = 3, rec = rec).filter { it.verb == verb }
    }

    private fun hitSkill(maxCount: String, hitDamage: String, handler: String) = load(
        """
        apiVersion: astera/v3
        kind: Skill
        id: hit-max-count
        machine:
          start: s
          states:
            s:
              for: 1
              do:
                - id: sel
                  op: hit
                  at: 0
                  anchor: caster
                  shape: {sphere: 8.0}
                  targets: enemies$maxCount
                  ignore_invulnerability: false$hitDamage
                  on:
                    hit:
                      - $handler
        """,
    )

    /** One `status` on the caster per hit event (the recorder resolves `event.victim` to every scripted victim, so events are counted this way). */
    private fun events(maxCount: String): Int {
        val handler = "{id: mark, op: status, target: caster, status: charge, stacks: 1, for: 20}"
        return played(hitSkill(maxCount, "", handler), "status").size
    }

    @Test
    fun maxCountBelowOneIsStillALoadError() {
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: bad-max-count
            machine:
              start: s
              states:
                s:
                  for: 4
                  do:
                    - {id: st, op: status, target: {kind: enemies, around: caster, radius: 8.0, max_count: 0}, status: shock, stacks: 1, for: 30}
            """.trimIndent(),
        )
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }
}
