package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The `keys` scale law MULTIPLIES the display's own `scale` (LawSegmenter: `scale = mul(scale, key)`), so a grow-in is authored as base = final size
 * and keys 0 -> 1 -> 0. A `scale: [0, 0, 0]` base with keys to the final size writes zeros for the whole life (the couture rapier overhead arc and
 * crescents were invisible for exactly this reason).
 */
class FrameDisplayKeysScaleScenarioTest : SkillEngineScenario() {
    private fun compiled(yaml: String): CompiledSkill = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> r.value
        is Result.Failure -> throw AssertionError("expected to load: ${r.error}")
    }

    private fun skill(base: String): CompiledSkill = compiled(
        """
        apiVersion: astera/v3
        kind: Skill
        id: keys-scale-probe
        frames:
          - {id: body, origin: caster, v: forward, w: up, s: v x w}
        machine:
          start: s
          states:
            s:
              for: 20
              then: end
              do:
                - {id: pane, op: display, kind: block, block: white_stained_glass, frame: body, pos: {v: 1.0, w: 1.0}, at: 0, for: 19, scale: $base, motion: [{law: keys, keys: [{t: 0, scale: [0, 0, 0]}, {t: 2, scale: [1, 1, 1]}, {t: 19, scale: [0, 0, 0]}]}]}
            end: {final: true}
        """
    )

    private fun largestWritten(base: String): Double {
        val rec = GatewayRecorder()
        play(skill(base), 1L, emptyList(), 22, rec)
        val sizes = rec.writtenPoses.map { it.third.size }
        println("KEYS-SCALE base=$base written sizes=${sizes.map { "(${it.x},${it.y},${it.z})" }}")
        return sizes.maxOfOrNull { it.z } ?: 0.0
    }

    @Test
    fun aZeroBaseZeroesTheKeysBecauseKeysScaleMultipliesTheBase() {
        assertThat(largestWritten("[0, 0, 0]")).describedAs("a keys scale multiplies the row's own scale: 0 x anything is 0").isEqualTo(0.0)
    }

    @Test
    fun theFinalSizeAsBaseWithKeysFromZeroToOneGrowsInAndOut() {
        assertThat(largestWritten("[0.05, 0.1, 0.59]")).describedAs("base = final size, keys 0 -> 1 -> 0 = the grow-in").isCloseTo(0.59, org.assertj.core.data.Offset.offset(1e-9))
    }

    @Test
    fun theCreamBloomPlateKeepsItsAuthoredPixelSizes() {
        val yaml = """
        apiVersion: astera/v3
        kind: Skill
        id: bloom-probe
        frames:
          - {id: body, origin: caster, v: forward, w: up, s: v x w}
        machine:
          start: s
          states:
            s:
              for: 8
              then: end
              do:
                - {id: bloom, op: display, kind: text, text: " ", background: "#EAD9B0", frame: body, pos: {v: 1.0, w: 1.0}, for: 6, scale: BASE, billboard: center, motion: [{law: keys, keys: [{t: 0, scale: {s: 4, w: 2, v: 1}, alpha: 0.2}, {t: 1, scale: {s: 12, w: 6, v: 1}}, {t: 5, scale: {s: 16, w: 8, v: 1}, alpha: 0}]}]}
            end: {final: true}
        """
        val sizes = mutableMapOf<String, Double>()
        for (base in listOf("[4, 2, 1]", "[1, 1, 1]")) {
            val rec = GatewayRecorder()
            play(compiled(yaml.replace("BASE", base)), 1L, emptyList(), 8, rec)
            sizes[base] = rec.writtenPoses.maxOf { it.third.size.x }
        }
        assertThat(sizes["[1, 1, 1]"]).describedAs("keys are absolute sizes on a unit base").isEqualTo(16.0)
        assertThat(sizes["[4, 2, 1]"]).describedAs("a non-unit base multiplies the keys").isEqualTo(64.0)
    }
}
