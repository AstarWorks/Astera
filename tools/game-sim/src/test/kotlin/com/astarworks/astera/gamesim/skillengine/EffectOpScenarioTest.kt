package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W2-02 acceptance (wave2-resolutions section 3: R5, R6, R9, R24, R25, R26, R32, R39, R41): the caster / target effect ops
 * lower to the rows the player and gateway read. The table-level cases need only the loader; driving the Effect rows into the
 * gateways needs the TablePlayer arms requested from w1b-02d / 02i.
 */
class EffectOpScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String, anchors: String = ""): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: effect-probe", "item: any",
        ) + (if (anchors.isEmpty()) emptyList() else listOf("anchors:", "  - $anchors")) +
            listOf("machine:", "  start: s", "  states:", "    s:", "      for: 40", "      do:") + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun compile(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun fails(yaml: String): Boolean = loadInline(yaml) is Result.Failure

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }

    private fun effect(s: CompiledSkill, op: String): Action.Effect =
        rows(s).map { it.action }.filterIsInstance<Action.Effect>().first { it.op.value == op }

    @Test
    fun dashRequiredFlagsMissingIsLoadError() {
        val flags = "direction: {forward: 1}, follow_pitch: false, stop_on_block: true, invulnerable: false, face_movement: false"
        assertThat(fails(skill("{id: d, op: dash, speed: 1.2, for: 8, $flags}"))).isFalse()
        for (missing in listOf("direction: {forward: 1}, ", "follow_pitch: false, ", "stop_on_block: true, ", "invulnerable: false, ", "face_movement: false")) {
            assertThat(fails(skill("{id: d, op: dash, speed: 1.2, for: 8, ${flags.replace(missing, "")}}"))).`as`("without $missing").isTrue()
        }
        assertThat(fails(skill("{id: d, op: dash, speed: 1.2, for: 8, radius: 2, $flags}"))).`as`("radius was removed").isTrue()
    }

    private fun effectCalls(s: CompiledSkill, rec: GatewayRecorder = GatewayRecorder(), events: List<com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent> = emptyList()) =
        play(s, 1L, events, null, rec).filter { it.verb == "effect" || it.verb == "item" }

    @Test
    fun screenFlashReachesTheGatewayAndRefusesBadInput() {
        val s = compile(
            skill(
                "{id: f1, op: screen_flash, at: 1, target: caster, overlay: gold_burst, color: \"#FFC84A\", fade_in: 0, stay: 2, fade_out: 3}",
                "{id: f2, op: screen_flash, at: 2, overlay: white}",
            ),
        )
        assertThat(effect(s, "screen_flash").payload.keys).contains("overlay", "color", "fade_in", "stay", "fade_out")
        assertThat(effectCalls(s).map { it.tick to it.payload }).contains(
            1 to "screen_flash=gold_burst color=#ffc84a in=0 stay=2 out=3",
            2 to "screen_flash=white color=default in=0 stay=1 out=2",
        )
        assertThat(fails(skill("{op: screen_flash, overlay: nope}"))).`as`("unknown overlay").isTrue()
        assertThat(fails(skill("{op: screen_flash, overlay: white, color: red}"))).`as`("colour is #RRGGBB").isTrue()
        assertThat(fails(skill("{op: screen_flash, color: \"#FFFFFF\"}"))).`as`("overlay is required").isTrue()
    }

}
