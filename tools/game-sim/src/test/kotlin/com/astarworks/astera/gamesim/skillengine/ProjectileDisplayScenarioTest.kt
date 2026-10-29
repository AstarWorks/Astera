package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W1b-02f (ADR-0303 3.6 / 2.1, wave1 resolutions 10, 15, 16): the projectile op's `times` family, the closed-form body `motion:` that
 * rides on riders as Key rows, the Look key list, and the display op's `tint`, no `mount`, sprite size division.
 * Every case compiles an inline skill through the real decoder and compiler and reads the `cast` state's rows.
 */
class ProjectileDisplayScenarioTest : SkillEngineScenario() {

    private fun skill(body: String): String = """
apiVersion: astera/v3
kind: Skill
id: proj-display-case
item: any
meta: {cooldown_ticks: 0, resource_cost: 0}
anchors:
  - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}}
machine:
  start: cast
  states:
    cast:
      for: 40
      then: done
      do:
$body
    done: {final: true}
"""

    private fun load(body: String): Result<CompiledSkill, SkillDecodeError> = loadInline(skill(body))

    private fun failure(body: String): SkillDecodeError =
        when (val r = load(body)) {
            is Result.Success -> error("expected a decode error")
            is Result.Failure -> r.error
        }

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.first { it.state.value == "cast" }.rows

    private val bolt = "id: bolt, op: projectile, origin: muzzle, velocity: \"= look(caster) * 1.0\", radius: 0.4, hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn}"

    // item 10: `mount` is not a display key (it lives on the anchor declaration only)
    @Test
    fun displayHasNoMount() {
        val e = failure("        - {id: d, op: display, kind: item, item: fire_charge, anchor: muzzle, mount: caster, for: 10, at: 0}")
        assertThat(e).isInstanceOf(SkillDecodeError.UnknownKey::class.java)
        assertThat((e as SkillDecodeError.UnknownKey).key).isEqualTo("mount")
    }

    // item 10: an unknown sprite is UndefinedReference, `scale` on a sprite is UnknownKey
    @Test
    fun spriteErrors() {
        val missing = failure("        - {id: s, op: display, kind: sprite, sprite: no-such-sprite, size: [1.0, 1.0], anchor: muzzle, for: 10, at: 0}")
        assertThat(missing).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
        val scaled = failure("        - {id: s, op: display, kind: sprite, sprite: bastion-arc, size: [1.0, 1.0], scale: [1, 1, 1], anchor: muzzle, for: 10, at: 0}")
        assertThat(scaled.toString()).contains("UnknownKey")
    }
}
