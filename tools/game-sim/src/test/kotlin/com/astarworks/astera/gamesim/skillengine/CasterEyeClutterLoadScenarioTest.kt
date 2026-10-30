package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.drycast.DryCast
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.gamesim.SimClutter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Load-time check 16 (engine-gaps-v2 1): a skill that clutters the caster's eye is a load error, the same compiler check the
 * server runs at boot and on `/astera skill reload`. Fixtures are inline; benign skills still load.
 */
class CasterEyeClutterLoadScenarioTest : SkillEngineScenario() {
    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: clutter-probe", "item: couture-rapier",
            "machine:", "  start: s", "  states:", "    s:", "      for: 12", "      do:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n")

    private fun failure(yaml: String): SkillDecodeError = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error
    }

    @Test
    fun particleFountainAtTheEyeIsALoadError() {
        val e = failure(skill("{id: fountain, op: particle, particle: flame, anchor: {of: caster, offset: {up: 1.62, forward: 0.3}}, count: 5, at: 0, every: 1, for: 10}"))
        assertThat(e).`as`(e.toString()).isInstanceOf(SkillDecodeError.CasterEyeClutter::class.java)
        assertThat(e.message).contains("flame")
    }

    @Test
    fun displayWallOneBlockAheadIsALoadError() {
        val e = failure(skill("{id: wall, op: display, kind: block, block: white_concrete, anchor: caster.eye, offset: {forward: 1.0}, scale: [3.0, 3.0, 0.1], at: 0, for: 10}"))
        assertThat(e).`as`(e.toString()).isInstanceOf(SkillDecodeError.DisplayCoverage::class.java)
    }

    @Test
    fun unsourcedParticleLifetimeIsALoadError() {
        val e = failure(skill("{id: p, op: particle, particle: not_a_real_particle, anchor: {of: caster, offset: {forward: 4.0}}, at: 0}"))
        assertThat(e.message).contains("not_a_real_particle")
    }

    @Test
    fun benignSkillLoads() {
        val r = loadInline(skill("{id: spark, op: particle, particle: crit, anchor: {of: caster, offset: {up: 1.0, forward: 3.0}}, count: 1, at: 0}"))
        assertThat(r).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun dryCastRecordsTheEmittedParticles() {
        val s = (loadInline(skill("{id: spark, op: particle, particle: crit, anchor: {of: caster, offset: {up: 1.0, forward: 3.0}}, count: 1, at: 0}")) as Result.Success).value
        val trace = DryCast.run(s, EngineConfig().casterEye.poses.first(), EngineConfig(), SimClutter.context())
        assertThat(trace.particles).isNotEmpty()
        assertThat(trace.particles.first().particle).isEqualTo("crit")
    }
}
