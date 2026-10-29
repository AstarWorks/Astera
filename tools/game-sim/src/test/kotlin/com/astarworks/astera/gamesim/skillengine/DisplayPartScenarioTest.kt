package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 sections 2 / 12: an item display takes a `part` (content/frames/<skill item>.yaml); `long` / `edge` / `face` of its rotations resolve on that part. */
class DisplayPartScenarioTest : SkillEngineScenario() {

    private fun skill(display: String): String = listOf(
        "apiVersion: astera/v3", "kind: Skill", "id: display-part-probe", "item: couture-rapier",
        "machine:", "  start: s", "  states:", "    s:", "      for: 10", "      do:",
        "        - $display",
    ).joinToString("\n") + "\n"

    @Test
    fun anItemDisplayReadsTheAxesOfItsPart() {
        val ok = loadInline(skill("{id: d, op: display, kind: item, item: main_hand, part: body, anchor: caster, rotate: [{axis: long, deg: 90}], for: 10}"))
        assertThat(ok).`as`(ok.toString()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun aPartTheItemDoesNotDeclareIsALoadError() {
        assertThat(loadInline(skill("{id: d, op: display, kind: item, item: main_hand, part: nope, anchor: caster, for: 10}"))).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun partBelongsToItemDisplays() {
        assertThat(loadInline(skill("{id: d, op: display, kind: block, block: stone, part: body, anchor: caster, for: 10}"))).isInstanceOf(Result.Failure::class.java)
    }
}
