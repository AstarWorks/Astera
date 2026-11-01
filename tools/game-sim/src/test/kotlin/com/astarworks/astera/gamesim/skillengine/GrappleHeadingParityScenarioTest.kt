package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * grapple-shot's wall pull is `dir: {v: pull_up, w: pull_forward}` on the built-in caster.feet frame (v horizontal forward, w up). The old row was the CEL
 * `forward(caster) * 1.3 + up(caster) * 0.4`. Both are played from the same pose (flat, and yaw 37 / pitch -20) and the world
 * impulse the player applies must be the same vector to 1e-6.
 */
class GrappleHeadingParityScenarioTest : SkillEngineScenario() {
    private fun skill(id: String, frames: String, row: String): CompiledSkill {
        val yaml = "apiVersion: astera/v3\nkind: Skill\nid: $id\n$frames" +
            "machine:\n  start: s\n  states:\n    s:\n      for: 6\n      then: end\n      do:\n        - $row\n    end: {final: true}\n"
        return when (val r = loadInline(yaml)) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("expected to load: ${r.error}")
        }
    }

    private fun impulse(skill: CompiledSkill, yaw: Double, pitch: Double): List<Double> {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        play(skill, 1L, emptyList(), 6, rec, yaw, pitch)
        assertThat(lastPlayer!!.faults()).isEmpty()
        val payload = rec.calls.single { it.verb == "effect" && it.payload.startsWith("impulse") }.payload
        return listOf("x", "y", "z").map { k -> Regex("$k=(-?[0-9.eE-]+)").find(payload)!!.groupValues[1].toDouble() }
    }

    @Test
    fun headingFrameImpulseEqualsTheOldForwardUpExpressionAtEveryPose() {
        val old = skill("pull-old", "", "{id: pull, op: impulse, at: 1, target: caster, vector: \"= forward(caster) * 1.3 + up(caster) * 0.4\"}")
        val new = skill(
            "pull-new",
            "",
            "{id: pull, op: impulse, at: 1, target: caster, frame: caster.feet, dir: {v: 1.3, w: 0.4}}",
        )
        for ((yaw, pitch) in listOf(0.0 to 0.0, 37.0 to -20.0, -120.0 to 45.0)) {
            val a = impulse(old, yaw, pitch)
            val b = impulse(new, yaw, pitch)
            println("GRAPPLE-HEADING yaw=$yaw pitch=$pitch old=$a new=$b")
            for (i in 0..2) assertThat(b[i]).describedAs("axis $i at yaw $yaw pitch $pitch").isCloseTo(a[i], org.assertj.core.data.Offset.offset(1e-6))
        }
    }
}
