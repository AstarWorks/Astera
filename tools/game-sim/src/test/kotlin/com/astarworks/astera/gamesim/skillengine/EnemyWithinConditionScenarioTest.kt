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
 * Founder 10/28 ("distance_less_than any enemy"): ADR-0303 3.8 names it as the condition `nearest_enemy_dist(caster) < r`
 * (an S/T/L class L world read). The guard runs through the real CEL path with the world-query host over the recorder's
 * entity query, so a named condition takes the branch only while an enemy (not an ally, not the caster) is within r.
 */
class EnemyWithinConditionScenarioTest : SkillEngineScenario() {
    private fun skill(): CompiledSkill = when (
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: enemy-within-test
            meta: {cooldown_ticks: 0, resource_cost: 0}
            conditions:
              enemy_near: "= nearest_enemy_dist(caster) < 5.0"
            machine:
              start: cast
              states:
                cast:
                  for: 6
                  then: done
                  do:
                    - {id: ping, op: status, at: 3, target: caster, status: charge, stacks: 1, for: 20, when: "cond:enemy_near"}
                done: {final: true}
            """.trimIndent(),
        )
    ) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun enemy(id: UUID, z: Double) = HitCandidate(id, Vec3(0.0, 0.0, z), 0.0, HitBox(Vec3(-0.3, 0.0, z - 0.3), Vec3(0.3, 1.8, z + 0.3)))

    private fun pings(configure: (GatewayRecorder) -> Unit): Int {
        val rec = GatewayRecorder().also { it.celGuards = true }
        configure(rec)
        val calls = play(skill(), seed = 1L, events = emptyList(), ticks = 8, rec = rec)
        return calls.count { it.verb == "status" }
    }

    @Test
    fun branchFollowsWhetherAnEnemyIsWithinRadius() {
        val foe = UUID(0L, 7L)
        assertThat(pings { }).describedAs("nobody around").isZero()
        assertThat(pings { it.victims = listOf(enemy(foe, 3.0)) }).describedAs("enemy 3 blocks away").isEqualTo(1)
        assertThat(pings { it.victims = listOf(enemy(foe, 9.0)) }).describedAs("enemy 9 blocks away").isZero()
        assertThat(pings { it.victims = listOf(enemy(foe, 9.0), enemy(UUID(0L, 8L), 4.0)) }).describedAs("any one enemy in range is enough").isEqualTo(1)
        assertThat(pings { it.victims = listOf(enemy(foe, 3.0)); it.allies += foe }).describedAs("an ally in range is not an enemy").isZero()
    }
}
