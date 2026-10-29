package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 item 5 (one instance-id rule) and the Ribbon / Rig / Dash field changes of items 2, 3 and 11 (W1b-02a). */
class OpSpecIdsScenarioTest : SkillEngineScenario() {

    private fun skill(item: String, vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: ids-probe", "item: $item",
            "anchors:", "  - {id: shears, anchor: caster.eye}",
            "machine:", "  start: s", "  states:", "    s:", "      for: 40", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun fails(item: String, vararg ops: String): Boolean = loadInline(skill(item, *ops)) is Result.Failure

    private val frame = "{id: tip, op: frame, anchor: shears, for: 20}"
    private val root = "{id: base, op: frame, anchor: shears, for: 20}"

    /** Example j: ids are `volley[k]/<op>` with no doubled segment (`ball/ball`) and `whoosh` carries no index. */
    @Test
    fun exampleJIdsMatchItem5() {
        val dump = ExpansionDump.render(loadExample("j-mythic-composition").table)
        for (k in 0..3) {
            assertThat(dump).contains("volley[$k]/ball")
            assertThat(dump).contains("volley[$k]/core")
            assertThat(dump).contains("volley[$k]/whoosh")
        }
        assertThat(dump).contains("volley[0]/trail[0]")
        assertThat(dump).doesNotContain("ball/ball").doesNotContain("whoosh[0]").doesNotContain("core/core").doesNotContain("trail/trail")
    }

    @Test
    fun ribbonTrailNeedsHistory() {
        assertThat(fails("any", frame, "{id: r, op: ribbon, of: tip, style: soft, width: 0.3, color: \"#FFFFFF\", for: 10}")).isTrue()
        assertThat(fails("any", frame, "{id: r, op: ribbon, of: tip, history: 4, style: soft, width: 0.3, color: \"#FFFFFF\", for: 10}")).isFalse()
    }

    @Test
    fun ribbonWidthIsFracXorBlocks() {
        val common = "id: r, op: ribbon, of: tip, history: 4, style: soft, color: \"#FFFFFF\", for: 10"
        assertThat(fails("any", frame, root, "{$common, root: base, width_frac: 1.0, width: 0.3}")).`as`("both").isTrue()
        assertThat(fails("any", frame, root, "{$common, root: base}")).`as`("neither").isTrue()
        assertThat(fails("any", frame, "{$common, width_frac: 1.0}")).`as`("width_frac without root").isTrue()
        assertThat(fails("any", frame, root, "{$common, root: base, width: 0.3}")).`as`("width with root").isTrue()
        assertThat(fails("any", frame, root, "{$common, root: base, width_frac: 1.0}")).`as`("width_frac with root").isFalse()
        assertThat(fails("any", frame, "{$common, width: 0.3}")).`as`("width without root").isFalse()
    }

    @Test
    fun dashWithMotionIsALoadError() {
        assertThat(fails("any", "{id: d, op: dash, speed: 1.6, for: 10, ease: out_cubic, direction: {forward: 1}, follow_pitch: false, stop_on_block: true, invulnerable: false, face_movement: false, motion: []}")).isTrue()
        assertThat(fails("any", "{id: d, op: dash, speed: 1.6, for: 10, ease: out_cubic, direction: {forward: 1}, follow_pitch: false, stop_on_block: true, invulnerable: false, face_movement: false}")).isFalse()
    }

    @Test
    fun rigWithItemIsALoadError() {
        assertThat(fails("couture-rapier", "{id: g, op: rig, parent: shears, item: couture-rapier, for: 10}")).isTrue()
    }

    @Test
    fun rigNeedsAConcreteSkillItem() {
        assertThat(fails("any", "{id: g, op: rig, parent: shears, for: 10}")).isTrue()
    }
}
