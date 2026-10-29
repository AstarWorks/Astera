package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * Compose rules of ADR-0303 (W1b-02b): item 1 (`every` with both `for` and `times`), `${p}` binding, check 5 (declared `emits`),
 * check 6 (`max_part_depth`), unknown CEL functions, and example j's op-level call sub-tables. Every case goes through the real
 * decoder and [com.astarworks.astera.core.application.skillengine.compose.SkillCompiler] (runs at the wave 1b gate).
 */
class ComposeRulesScenarioTest : SkillEngineScenario() {

    private fun skill(body: String, header: String = "", forTicks: Int = 40): String = """
apiVersion: astera/v3
kind: Skill
id: compose-case
item: any
meta: {cooldown_ticks: 0, resource_cost: 0}
$header
anchors:
  - {id: muzzle, anchor: caster.eye, offset: {forward: 0.6}, mode: follow}
machine:
  start: cast
  states:
    cast:
      for: $forTicks
      then: done
      do:
$body
    done: {final: true}
"""

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error, the skill compiled")
        is Result.Failure -> r.error.toString()
    }

    private fun snd(extra: String): String =
        "        - {id: tick, op: sound, sound: block.note_block.hat, anchor: muzzle, volume: 1.0, pitch: 1.0, $extra}"

    @Test
    fun everyWithForAndTimesIsRejected() {
        val msg = failure(skill(snd("at: 0, every: 4, for: 12, times: 2")))
        assertThat(msg).contains("write for or times, not both")
    }

    @Test
    fun declaredEmitNeverEmittedIsCheckFive() {
        val msg = failure(skill(snd("at: 0"), "emits: [ghost]"))
        assertThat(msg).contains("emits 'ghost' is declared but never emitted")
    }

    private fun chain(depth: Int): String {
        val sb = StringBuilder()
        for (k in 0 until depth) {
            val next = if (k + 1 < depth) "      use: p${k + 1}\n      then: end" else "      for: 1\n      then: end"
            sb.append(
                "apiVersion: astera/v3\nkind: Part\nid: p$k\nemits: [end]\nmachine:\n  start: s\n  states:\n    s:\n$next\n    end: {final: true}\n---\n",
            )
        }
        sb.append(skill("        - {id: e, emit: ping, at: 0}").replace("    done: {final: true}", "    done: {final: true}"))
        // the main skill calls p0 from a state
        return sb.toString().replace(
            "    cast:\n      for: 40\n      then: done\n",
            "    cast:\n      use: p0\n      then: done\n",
        ).replace("      do:\n        - {id: e, emit: ping, at: 0}\n", "")
    }

    @Test
    fun partDepthAtTheLimitPassesAndOnePastFails() {
        assertThat(loadInline(chain(6))).isInstanceOf(Result.Success::class.java)
        assertThat(failure(chain(7))).containsIgnoringCase("deeper than")
    }

    private fun texts(prefix: String, n: Int, at: Int, forTicks: Int): String =
        (0 until n).joinToString("\n") {
            "        - {id: $prefix$it, op: display, kind: text, text: \"x\", anchor: muzzle, at: $at, for: $forTicks}"
        }

    @Test
    fun displayCapCountsWhatIsAliveAtTheEndOfEachTick() {
        // 40 displays spawned at tick 5 for 10 are removed in tick 15's cleanup; 40 more spawn on tick 15. Never alive together: peak 40.
        val touching = texts("a", 40, 5, 10) + "\n" + texts("b", 40, 15, 5)
        assertThat(loadInline(skill(touching))).isInstanceOf(Result.Success::class.java)
        // one tick earlier they do coexist (tick 14): 80 > 64.
        val overlapping = texts("a", 40, 5, 10) + "\n" + texts("b", 40, 14, 5)
        assertThat(failure(skill(overlapping))).contains("LiveDisplayCap(peak=80")
    }

    @Test
    fun unknownCelFunctionIsALoadError() {
        val msg = failure(skill("        - {id: e, op: sound, sound: block.note_block.hat, anchor: muzzle, volume: \"= nosuchfn(1)\", pitch: 1.0, at: 0}"))
        assertThat(msg).containsIgnoringCase("nosuchfn")
    }

    private val petals =
        "        - {id: petal, op: frame, anchor: muzzle, times: 8, offset: {left: \"= cosd(ang) * r\", forward: \"= sind(ang) * r\"}, at: 0, for: 10}"

    @Test
    fun letBindingsFoldPerCopy() {
        val yaml = skill(petals, "let:\n  ang: \"= i * 360 / n\"\n  r: \"= 0.6 + 0.1 * i\"")
        val s = when (val r = loadInline(yaml)) {
            is Result.Success -> r.value
            is Result.Failure -> error("did not load: ${r.error}")
        }
        val rows = s.table.states.flatMap { it.rows }.filter { it.action is Action.Spawn }.sortedBy { it.instance.path.last().index }
        assertThat(rows).hasSize(8)
        rows.forEachIndexed { k, row ->
            @Suppress("UNCHECKED_CAST")
            val v = ((row.action as Action.Spawn).pose.position as Val.Known<Vector3d>).value
            val r = 0.6 + 0.1 * k
            assertThat(v.length()).isCloseTo(r, within(1e-9))
            assertThat(Math.abs(v.z)).isCloseTo(Math.abs(Math.sin(Math.toRadians(k * 45.0)) * r), within(1e-9))
        }
    }

    @Test
    fun letNameCollisionIsLoadError() {
        assertThat(failure(skill(petals, "let:\n  i: \"= 1\""))).contains("'i' is a builtin copy index and cannot be rebound")
        assertThat(failure(skill(petals, "let:\n  muzzle: \"= 1\""))).contains("cannot be rebound by a let")
        assertThat(failure(skill(petals, "let:\n  cosd: \"= 1\""))).contains("expression function")
    }

    @Test
    fun letUsedInTimingNamesTheLet() {
        val op = "        - {id: petal, op: display, kind: text, text: \"x\", anchor: muzzle, at: \"= start_tick\", for: 5}"
        val msg = failure(skill(op, "let:\n  start_tick: \"= dist(caster, target) / 0.8\""))
        assertThat(msg).contains("display 'petal' at: '= start_tick' uses let 'start_tick' = (dist(caster, target) / 0.8)").contains("(C5)")
    }

    @Test
    fun victimOutsideHandlerIsLoadError() {
        val op = "        - {id: mark, op: sound, sound: block.note_block.hat, anchor: muzzle, volume: \"= dot(pos(victim), vec(0, 1, 0))\", pitch: 1.0, at: 0}"
        assertThat(failure(skill(op))).contains("'victim' is only bound inside a hit handler").contains("state 'cast' do: list")
        val display = "        - {id: mark, op: frame, anchor: muzzle, offset: {up: \"= dot(pos(victim), vec(0, 1, 0))\"}, at: 0, for: 5}"
        assertThat(failure(skill(display))).contains("'victim' is only bound inside a hit handler").contains("machine.states.cast.do[0]")
    }

    @Test
    fun targetAttackerNamesEventAttacker() {
        val op = "        - {id: ouch, op: damage, at: 0, target: attacker, damage: {physical: 1.0}}"
        assertThat(failure(skill(op))).contains("target 'attacker' is spelled event.attacker")
    }
}
