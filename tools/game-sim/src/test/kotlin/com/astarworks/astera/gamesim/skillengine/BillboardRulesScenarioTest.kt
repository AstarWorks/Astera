package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 15.7 / engine-gaps-v2 section 4: `billboard` is a client facing for sprite / text only, with no rotation law and no translation. */
class BillboardRulesScenarioTest : SkillEngineScenario() {

    private fun skill(op: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: billboard-probe", "item: couture-rapier",
            "machine:", "  start: s", "  states:", "    s:", "      for: 100", "      enter:", "        - $op",
        )
        ).joinToString("\n") + "\n"

    private fun error(op: String): String = when (val r = loadInline(skill(op))) {
        is Result.Failure -> r.error.toString()
        is Result.Success -> ""
    }

    private val sprite = "{id: d, op: display, kind: sprite, sprite: arcane-slash, anchor: caster, size: [1, 1], for: 20, billboard: center"

    @Test
    fun spriteBillboardWithoutLawLoads() {
        assertThat(loadInline(skill("$sprite}"))).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun billboardBlockDisplayIsRejected() {
        assertThat(error("{id: d, op: display, kind: block, block: stone, anchor: caster, for: 20, billboard: center}")).contains("billboard").contains("sprite or text")
    }

    @Test
    fun billboardItemDisplayIsRejected() {
        assertThat(error("{id: d, op: display, kind: item, item: couture-rapier, anchor: caster, for: 20, billboard: vertical}")).contains("sprite or text")
    }

    @Test
    fun billboardWithSpinIsRejected() {
        assertThat(error("$sprite, motion: [{law: spin, axis: forward, deg_per_tick: 10, at: 0, for: 20}]}")).contains("rotation law")
    }

    @Test
    fun billboardWithAlignIsRejected() {
        assertThat(error("$sprite, motion: [{law: align, axis: forward, to: world.up, at: 0, for: 6}]}")).contains("rotation law")
    }

    @Test
    fun billboardWithPoseKeysIsRejected() {
        assertThat(error("$sprite, motion: [{law: keys, keys: [{t: 0, pose: [{axis: up, deg: 0}]}, {t: 10, pose: [{axis: up, deg: 90}]}]}]}")).contains("rotation law")
    }

    @Test
    fun billboardWithTranslationIsRejected() {
        assertThat(error("$sprite, translation: {forward: 1}}")).contains("translation")
    }
}
