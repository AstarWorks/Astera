package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-22 W1-11 (ADR-0303 10.1 / 3.7.1): the caster-motion ops `dash`, `reel`, `rig` and `emit` lower to Effect / Emit rows.
 * Runs at the wave 1b gate (needs the document decoder, unroller and harness of W1-01 / W1-18 / W1-20).
 */
class CasterMotionOpsScenarioTest : SkillEngineScenario() {

    private val dashSkill = """
        apiVersion: astera/v3
        kind: Skill
        id: dash-emit
        item: couture-rapier
        machine:
          start: go
          states:
            go:
              for: 12
              do:
                - {id: dash, op: dash, at: 3, speed: 1.5, for: 6, direction: {forward: 1}, follow_pitch: false, stop_on_block: true, invulnerable: false, face_movement: false}
                - {emit: done, at: 9}
    """.trimIndent()

    @Test
    fun dashWithoutSpanIsRejected() {
        val r = loadInline(dashSkill.replace(" for: 6,", ""))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }
}
