package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The id fields of particle (`block`, `item`) and display (`item`, `sprite`) take `= CEL` over `i` / `n` and per-index lists under `times`,
 * exactly like display `block` (R28): one id per copy baked into the table, a wrong list length or a non-folding id a named load error.
 */
class IdFieldsIndexedScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: id-fields", "item: couture-rapier",
            "anchors:", "  - {id: body, anchor: caster, mode: follow}",
            "machine:", "  start: s", "  states:", "    s:", "      for: 100", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun compiled(vararg ops: String): CompiledSkill =
        when (val r = loadInline(skill(*ops))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }

    private fun failure(vararg ops: String): String =
        when (val r = loadInline(skill(*ops))) {
            is Result.Success -> throw AssertionError("expected a load error")
            is Result.Failure -> r.error.toString()
        }

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }

    private fun lookField(s: CompiledSkill, field: String): List<Any?> =
        rows(s).filter { it.action is Action.Spawn }.sortedBy { it.instance.toString() }
            .map { ((it.action as Action.Spawn).look.fields[field] as Val.Known<*>).value }

    private fun particleData(s: CompiledSkill): List<ParticleData?> =
        rows(s).filter { it.action is Action.Particle }.sortedBy { it.instance.toString() }.map { (it.action as Action.Particle).data }

    @Test
    fun particleBlockAndItemTakeListsAndCel() {
        val list = compiled("{id: p, op: particle, particle: block, block: [stone, dirt, gravel], anchor: body, times: 3}")
        assertThat(particleData(list)).containsExactly(ParticleData.Block("stone"), ParticleData.Block("dirt"), ParticleData.Block("gravel"))
        val cel = compiled("{id: p, op: particle, particle: block, block: \"= int(i) == 0 ? 'stone' : 'dirt'\", anchor: body, times: 2}")
        assertThat(particleData(cel)).containsExactly(ParticleData.Block("stone"), ParticleData.Block("dirt"))
        val item = compiled("{id: p, op: particle, particle: item, item: [diamond, emerald], anchor: body, times: 2}")
        assertThat(particleData(item)).containsExactly(ParticleData.Item("diamond"), ParticleData.Item("emerald"))
    }

    @Test
    fun displayItemAndSpriteTakeListsAndCel() {
        val items = compiled("{id: d, op: display, kind: item, item: [couture-rapier, bastion-greatsword], anchor: caster, times: 2, for: 20}")
        assertThat(lookField(items, "item")).containsExactly("couture-rapier", "bastion-greatsword")
        val sprites = compiled("{id: d, op: display, kind: sprite, sprite: [bastion-arc, bastion-crest], size: [1.0, 1.0], anchor: caster, times: 2, for: 20}")
        assertThat(lookField(sprites, "sprite")).containsExactly("bastion-arc", "bastion-crest")
        val cel = compiled("{id: d, op: display, kind: sprite, sprite: \"= int(i) == 0 ? 'bastion-arc' : 'bastion-crest'\", size: [1.0, 1.0], anchor: caster, times: 2, for: 20}")
        assertThat(lookField(cel, "sprite")).containsExactly("bastion-arc", "bastion-crest")
    }

    @Test
    fun badIdFieldsAreNamedLoadErrors() {
        failure("{id: p, op: particle, particle: block, block: [stone, dirt], anchor: body, times: 3}")
        failure("{id: d, op: display, kind: sprite, sprite: [bastion-arc, no-such-sprite], size: [1.0, 1.0], anchor: caster, times: 2, for: 20}")
        failure("{id: d, op: display, kind: sprite, sprite: \"= rand() > 0.5 ? 'bastion-arc' : 'bastion-crest'\", size: [1.0, 1.0], anchor: caster, for: 20}")
        failure("{id: p, op: particle, particle: block, block: \"= rand() > 0.5 ? 'stone' : 'dirt'\", anchor: body}")
    }

    @Test
    fun hitVictimOutsideOnHitIsALoadError() {
        failure("{id: c, op: camera_shake, intensity: 1.0, direction: from_hit, for: 4}")
    }
}
