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
 * A frame anchored to the bare `victim` outlives the hit row that spawned it: it ends by its own `for:` (natural finalize) or with the
 * state / the cast (cancel), where no row binding exists. The instance keeps the binding it was spawned under, so Paper's
 * `victim` anchor still resolves and neither end throws (e2e: `live cell 'victim' is not resolvable` at endInstance).
 */
class VictimAnchoredInstanceScenarioTest : SkillEngineScenario() {
    private fun load(frameFor: Int, stateFor: Int): CompiledSkill = when (
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: victim-anchored
            machine:
              start: s
              states:
                s:
                  for: $stateFor
                  do:
                    - id: sel
                      op: hit
                      at: 0
                      anchor: caster
                      shape: {sphere: 8.0}
                      targets: enemies
                      ignore_invulnerability: false
                      damage: {physical: 1.0}
                      on:
                        hit:
                          - {id: mark, op: frame, anchor: victim, for: $frameFor}
            """.trimIndent(),
        )
    ) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun run(skill: CompiledSkill, cancelAtEnd: Boolean = false): List<RecordedCall> {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = listOf(HitCandidate(UUID(0L, 11L), Vec3(0.0, 0.0, 2.0), 0.0, HitBox(Vec3(-0.3, 0.0, 1.7), Vec3(0.3, 1.8, 2.3))))
        return play(skill, seed = 1L, events = emptyList(), ticks = 12, rec = rec, cancelAtEnd = cancelAtEnd)
    }

    @Test
    fun frameEndingByItsOwnForResolvesTheVictimAnchor() {
        assertThat(run(load(frameFor = 3, stateFor = 10))).isNotNull
    }

    @Test
    fun frameStillRunningWhenTheCastIsCancelledResolvesTheVictimAnchor() {
        assertThat(run(load(frameFor = 40, stateFor = 40), cancelAtEnd = true)).isNotNull
    }
}
