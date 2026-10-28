package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.Val
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

    private fun compile(body: String): CompiledSkill =
        when (val r = load(body)) {
            is Result.Success -> r.value
            is Result.Failure -> error("expected the skill to load, got ${r.error}")
        }

    private fun failure(body: String): SkillDecodeError =
        when (val r = load(body)) {
            is Result.Success -> error("expected a decode error")
            is Result.Failure -> r.error
        }

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.first { it.state.value == "cast" }.rows

    private val bolt = "id: bolt, op: projectile, origin: muzzle, velocity: \"= look(caster) * 1.0\", radius: 0.4, hit: {targets: enemies, once: true}, block: {mode: despawn}"

    private fun p(extra: String) = "        - {$bolt, $extra}"

    private fun bodySpawns(s: CompiledSkill) = rows(s).filter { (it.action as? Action.Spawn)?.kind == SpawnKind.PROJECTILE_BODY }

    private fun bodyLook(s: CompiledSkill): Map<String, Val<Any>> =
        (bodySpawns(s).first().action as Action.Spawn).look.fields

    // item 16: `times` without `every` is a family of simultaneous projectiles
    @Test
    fun timesIsAFamily() {
        val s = compile(p("speed: 1.0, for: 20, at: 0, times: 3"))
        assertThat(bodySpawns(s).map { it.instance.text }).containsExactly("cast/bolt[0]", "cast/bolt[1]", "cast/bolt[2]")
    }

    // item 16: the body emits no Key / Teleport rows (the laws are closed-form and composed into the riders)
    @Test
    fun bodyEmitsNoKeyRows() {
        val s = compile(p("speed: 1.0, for: 20, motion: [{law: spin, axis: up, deg_per_tick: 5, for: 10}]"))
        val onBody = rows(s).filter { it.instance.text == "cast/bolt" && (it.action is Action.Key || it.action is Action.Teleport) }
        assertThat(onBody).isEmpty()
    }

    // item 16: flight length is `for` and/or `range`, whichever ends first
    @Test
    fun forAndRangeEnd() {
        val byFor = rows(compile(p("speed: 1.0, for: 12"))).single { it.action is Action.End && it.instance.text == "cast/bolt" }
        assertThat(byFor.tick).isEqualTo(12)
        val byRange = rows(compile(p("speed: 1.0, range: 8"))).single { it.action is Action.End && it.instance.text == "cast/bolt" }
        assertThat(byRange.tick).isEqualTo(8)
        val both = rows(compile(p("speed: 1.0, range: 30, for: 10"))).single { it.action is Action.End && it.instance.text == "cast/bolt" }
        assertThat(both.tick).isEqualTo(10)
    }

    // item 16: constant speed and the ramp print their own Look keys
    @Test
    fun lookKeys() {
        // gravity / drag have no hidden default (ADR-0303 3.6): a Look key exists only for a field the skill wrote
        val constant = bodyLook(compile(p("speed: 1.5, for: 20")))
        assertThat(constant).containsKeys("speed", "radius")
        assertThat(constant).doesNotContainKeys("speed.from", "speed.to", "gravity", "drag")
        assertThat(bodyLook(compile(p("speed: 1.5, for: 20, gravity: 0.02, drag: 0.01")))).containsKeys("speed", "radius", "gravity", "drag")
        val ramp = bodyLook(compile(p("speed: {from: 0.5, to: 2.0, for: 10, ease: linear}, for: 20")))
        assertThat(ramp).containsKeys("speed.from", "speed.to", "speed.for", "speed.ease")
    }

    // item 16: a rider anchored on the body composes the body's laws into its own Key rows
    @Test
    fun riderKeysComposeAnchorLaws() {
        val s = compile(
            p("speed: 1.0, for: 20, motion: [{law: spin, axis: up, deg_per_tick: 10, for: 10}]") + "\n" +
                "        - {id: rider, op: display, kind: item, item: fire_charge, anchor: bolt, for: 20, at: 0}",
        )
        val keys = rows(s).filter { it.instance.text == "cast/rider" && it.action is Action.Key }
        assertThat(keys).isNotEmpty()
    }

    // item 5: the display's own instance id is lc.self (indexed once by the unroller, never twice)
    @Test
    fun repeatingDisplayIsIndexedOnce() {
        val s = compile("        - {id: puff, op: display, kind: item, item: fire_charge, anchor: muzzle, every: 5, lasts: 4, for: 15, at: 0}")
        val spawns = rows(s).filter { it.action is Action.Spawn }
        assertThat(spawns.map { it.instance.text }).containsExactly("cast/puff[0]", "cast/puff[1]", "cast/puff[2]")
    }

    // item 10: `mount` is not a display key (it lives on the anchor declaration only)
    @Test
    fun displayHasNoMount() {
        val e = failure("        - {id: d, op: display, kind: item, item: fire_charge, anchor: muzzle, mount: caster, for: 10, at: 0}")
        assertThat(e).isInstanceOf(SkillDecodeError.UnknownKey::class.java)
        assertThat((e as SkillDecodeError.UnknownKey).key).isEqualTo("mount")
    }

    // ADR-0303 2.1 rev4.3 (R33): the sprite colour key is `color` (ARGB hex); the v2-era `tint` is gone
    @Test
    fun spriteColorReplacesTint() {
        val sprite = "id: s, op: display, kind: sprite, sprite: bastion-arc, size: [1.0, 1.0], anchor: muzzle, for: 10, at: 0"
        val ok = compile("        - {$sprite, color: \"#ff8800\"}")
        val look = (rows(ok).first { it.action is Action.Spawn }.action as Action.Spawn).look.fields
        assertThat(look).containsKey("color")
        assertThat(look).doesNotContainKey("tint")
        val e = failure("        - {$sprite, tint: \"#ff8800\"}")
        assertThat(e).isInstanceOf(SkillDecodeError.UnknownKey::class.java)
    }

    // item 10: size (blocks) is divided by the natural size at lowering; Look carries size and the vanilla scale
    @Test
    fun spriteSizeIsDividedByNaturalSize() {
        val s = compile("        - {id: s, op: display, kind: sprite, sprite: bastion-arc, size: [2.0, 1.0], anchor: muzzle, for: 10, at: 0}")
        val spawn = rows(s).first { it.action is Action.Spawn }.action as Action.Spawn
        assertThat(spawn.look.fields).containsKeys("size", "scale")
        assertThat(spawn.pose.scale).isEqualTo(spawn.look.fields["scale"])
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
