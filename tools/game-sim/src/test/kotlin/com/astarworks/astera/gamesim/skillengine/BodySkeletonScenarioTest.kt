package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.gamesim.SimClips
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The named body points (`hand_r`, `hand_l`) live once in `content/frames/body.yaml`, not in each clip (audit G6 / P17): a `body` op
 * reads them through any clip, through the one shared player path used by sim and Paper.
 */
class BodySkeletonScenarioTest : SkillEngineScenario() {
    private fun skill(anim: String): CompiledSkill = when (
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: body-skeleton-probe
            anchors:
              - {id: right_hand, anchor: body_anim.hand_r, mount: caster}
              - {id: left_hand, anchor: body_anim.hand_l, mount: caster}
            machine:
              start: s
              world:
                - {id: body_anim, op: body, anim: $anim, speed: 1.0, for: 12}
              states:
                s:
                  for: 12
                  enter:
                    - {id: right, op: particle, particle: flame, anchor: right_hand, at: 0, every: 4, for: 9}
                    - {id: left, op: particle, particle: crit, anchor: left_hand, at: 0, every: 4, for: 9}
            """.trimIndent(),
        )
    ) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    @Test
    fun skeletonDeclaresBothHandsOnce() {
        assertThat(SimClips.bodySkeleton().points.keys).containsExactlyInAnyOrder("hand_r", "hand_l")
    }

    @Test
    fun everyShippedClipReadsTheSameHandsFromTheSkeleton() {
        for (clip in listOf("bastion-s1-body", "bastion-s2-body", "couture-s2-body", "couture-s2-body-b")) {
            val rec = GatewayRecorder()
            play(skill(clip), seed = 1L, events = emptyList(), ticks = 12, rec = rec)
            val right = rec.emittedAt.filter { it.second == "flame" }
            val left = rec.emittedAt.filter { it.second == "crit" }
            assertThat(right.map { it.first }).describedAs(clip).containsExactly(0, 4, 8)
            assertThat(left.map { it.first }).describedAs(clip).containsExactly(0, 4, 8)
            // each hand follows its own animated arm
            assertThat(right.first().third).describedAs("$clip hand_r moves").isNotEqualTo(right.last().third)
            assertThat(left.first().third).describedAs("$clip hands are two points").isNotEqualTo(right.first().third)
        }
    }
}
