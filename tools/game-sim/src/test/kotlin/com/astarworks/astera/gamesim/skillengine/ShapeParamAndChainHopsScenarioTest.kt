package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0303 design-gaps section 4: hit and world shape sizes take `${param}` (hit = live cell, world = folded at load), and a `chain:`
 * call hands each hop its own values (`hop` / `hops` expressions, a `with:` list of exactly `hops` entries, timing folded per copy).
 */
class ShapeParamAndChainHopsScenarioTest : SkillEngineScenario() {
    private fun load(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error.message
    }

    private fun dummy(i: Long, z: Double) =
        HitCandidate(UUID(0L, 10L + i), Vec3(0.0, 0.0, z), 0.0, HitBox(Vec3(-0.3, 0.0, z - 0.3), Vec3(0.3, 1.8, z + 0.3)), 1.62)

    private fun sphereSkill(radius: Double) = load(
        """
        apiVersion: astera/v3
        kind: Skill
        id: sphere-param
        params:
          radius: {type: blocks, default: $radius}
        machine:
          start: s
          states:
            s:
              for: 5
              do:
                - {id: blast, op: hit, at: 0, anchor: caster, targets: enemies, shape: {sphere: "${'$'}{radius}"}, window: once, ignore_invulnerability: false, damage: {fire: 5}}
        """.trimIndent(),
    )

    @Test
    fun sphereRadiusFromParam() {
        val victims = listOf(dummy(1, 3.0), dummy(2, 8.0))
        fun hit(radius: Double): List<String> {
            val rec = GatewayRecorder()
            rec.victims = victims
            return play(sphereSkill(radius), seed = 1L, events = emptyList(), ticks = 5, rec = rec).filter { it.verb == "damage" }.map { it.instance }
        }
        assertThat(hit(5.0)).containsExactly(UUID(0L, 11L).toString())
        assertThat(hit(10.0)).containsExactlyInAnyOrder(UUID(0L, 11L).toString(), UUID(0L, 12L).toString())
    }

    @Test
    fun wallSizeFromParam() {
        val skill = load(
            """
            apiVersion: astera/v3
            kind: Skill
            id: wall-param
            params:
              width:  {type: number, default: 5}
              height: {type: number, default: 4}
            anchors:
              - {id: body, anchor: caster, mode: snapshot}
            machine:
              start: s
              states:
                s:
                  for: 20
                  do:
                    - {id: p, op: place_shape, anchor: body, shape: {wall: {width: "${'$'}{width}", height: "${'$'}{height}"}}, block: minecraft:stone, consume: none, ripple: 0, revert: 40, outside_arena: false}
            """.trimIndent(),
        )
        val spec = skill.table.states.flatMap { it.rows }.map { it.action }.filterIsInstance<Action.World>().single().payload["spec"] as Val.Known<*>
        assertThat(spec.value.toString()).contains("width=5").contains("height=4")
    }

    private fun chainSkill(withLine: String, shock: String = "= 20 - 4 * hop"): String = """
        apiVersion: astera/v3
        kind: Skill
        id: chain-hops
        fragments:
          arc:
            params:
              decay: {type: number, default: 1.0}
            do:
              - {id: dmg, op: damage, target: victim, damage: {fire: "= 20 * pow(decay, hop)"}}
              - {id: shock, op: status, target: victim, status: shock, stacks: 1, for: "$shock"}
        machine:
          start: cast
          world:
            - id: bolt
              op: hit
              at: 0
              anchor: caster
              shape: {sphere: 30.0}
              targets: enemies
              max_count: 1
              ignore_invulnerability: false
              damage: {fire: 5}
              on:
                hit:
                  - {id: jump, op: use, use: "fragment:arc", chain: {hops: 3, every: 3, pick: {kind: enemies, around: from, radius: 4.5, nearest: 1, exclude: chain}}$withLine}
          states:
            cast:
              for: 30
              do: []
    """.trimIndent()

    private fun fireDamage(skill: CompiledSkill): List<Pair<Int, Double>> {
        val rec = GatewayRecorder()
        rec.victims = (1L..4L).map { dummy(it, it * 3.0) }
        return play(skill, seed = 1L, events = emptyList(), ticks = 30, rec = rec).filter { it.verb == "damage" }.map { it.tick to it.toString().let { s -> Regex("""amount=([0-9.]+)""").find(s)?.groupValues?.get(1)?.toDouble() ?: Double.NaN } }
    }

    @Test
    fun hopExpressionGivesEachHopItsOwnDamage() {
        val dump = ExpansionDump.render(load(chainSkill(", with: {decay: 0.5}")).table)
        assertThat(dump).contains("jump[1]/dmg").contains("jump[2]/dmg").contains("jump[3]/dmg")
        for (hop in 1..3) assertThat(dump).contains("20_*_pow(0.5,_$hop.0)") // folded per copy, not one shared cell
    }

    @Test
    fun withListIsReadPerHop() {
        val dump = ExpansionDump.render(load(chainSkill(", with: {decay: [0.7, 0.5, 0.3]}")).table)
        for ((hop, d) in listOf("0.7", "0.5", "0.3").withIndex()) assertThat(dump).contains("20_*_pow($d,_${hop + 1}.0)")
        assertThat(failure(chainSkill(", with: {decay: [0.7, 0.5]}"))).contains("chain 'jump': with.decay has 2 entries but hops is 3 (one entry per hop)")
    }

    @Test
    fun hopTimingFoldsPerCopyAndMustStayPositive() {
        val ok = ExpansionDump.render(load(chainSkill("", shock = "= 20 - 4 * hop")).table)
        assertThat(ok).contains("jump[1]/shock").contains("jump[3]/shock")
        val bad = failure(chainSkill("", shock = "= 12 - 4 * hop"))
        assertThat(bad).contains("jump[3]/shock.for: = 12 - 4 * 3.0 folds to 0")
    }
}
