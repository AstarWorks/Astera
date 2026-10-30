package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0303 rev4.10 (callee frames): a called skill / skill-local fragment names a `frames_in` frame wherever a point is named
 * (particle anchor, sound anchor, area `around`), compiles on its own with that name in its static scope, and at a call site
 * the name means the caller's frame in sim and on Paper alike. An unbound `frames_in` stays a load error naming the frame.
 */
class CalleeFramesInScenarioTest : SkillEngineScenario() {
    private val spotCallee = """
        ---
        apiVersion: astera/v3
        kind: Skill
        id: spark
        meta: {cooldown_ticks: 0, resource_cost: 0}
        frames_in: {spot: anchor}
        machine:
          start: s
          states:
            s:
              for: 2
              then: e
              enter:
                - {id: pf, op: particle, particle: flame, anchor: spot, count: 3, offset: [0.1, 0.1, 0.1], extra: 0.0, at: 0}
                - {id: pg, op: particle, particle: smoke, anchor: spot, shift: {up: 1.0}, count: 2, offset: [0.0, 0.0, 0.0], extra: 0.0, at: 0}
                - {id: sn, op: sound, sound: entity.generic.explode, anchor: spot, volume: 0.5, pitch: 1.0, at: 0}
            e: {final: true}
    """.trimIndent()

    private fun caller(call: String) = """
        ---
        apiVersion: astera/v3
        kind: Skill
        id: caller
        meta: {cooldown_ticks: 0, resource_cost: 0}
        machine:
          start: s
          states:
            s:
              for: 4
              then: e
              enter:
                - $call
            e: {final: true}
    """.trimIndent()

    private fun ok(r: Result<CompiledSkill, SkillDecodeError>): CompiledSkill = when (r) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    @Test
    fun calleeCompilesStandaloneWithItsFramesInAsPoints() {
        ok(loadInline(spotCallee))
    }

    @Test
    fun calleeParticleAndSoundLandOnTheCallersFrame() {
        val skill = ok(loadInline(caller("{id: c, use: skill:spark, frames: {spot: caster}}") + "\n" + spotCallee))
        val calls = play(skill, seed = 1L, ticks = 6)
        assertThat(calls.filter { it.verb == "particle" }).hasSize(2)
        assertThat(calls.filter { it.verb == "sound" }).hasSize(1)
    }

    @Test
    fun unboundFramesInAtTheCallSiteIsALoadErrorNamingTheFrame() {
        val r = loadInline(caller("{id: c, use: skill:spark}") + "\n" + spotCallee)
        val e = (r as Result.Failure).error
        assertThat(e.message).contains("spot")
    }

    @Test
    fun castingACalleeDirectlyIsRefusedNamingTheFrame() {
        val skill = ok(loadInline(spotCallee))
        assertThatThrownBy { play(skill, seed = 1L, ticks = 4) }.hasMessageContaining("CalleeOnly").hasMessageContaining("spot")
    }

    private fun fragmentSkill() = """
        apiVersion: astera/v3
        kind: Skill
        id: splash-owner
        meta: {cooldown_ticks: 0, resource_cost: 0}
        fragments:
          splash:
            params: {power: {type: number, required: true}}
            frames_in: {origin: anchor}
            do:
              - {id: dmg, op: damage, target: {kind: enemies, around: origin, radius: 2.5, max_count: 8, include_self: false}, damage: {fire: "= 6 * ${'$'}{power}"}}
        machine:
          start: s
          states:
            s:
              for: 6
              then: e
              do:
                - id: sel
                  op: hit
                  at: 0
                  anchor: caster
                  shape: {sphere: 8.0}
                  targets: enemies
                  ignore_invulnerability: false
                  damage: {physical: 1.0}
                  on:
                    hit:
                      - {id: sp, use: fragment:splash, with: {power: 2}, frames: {origin: contact}}
            e: {final: true}
    """.trimIndent()

    @Test
    fun fragmentAreaDamageAroundFramesInHitsTheEnemyAtTheCallersContact() {
        val skill = ok(loadInline(fragmentSkill()))
        val enemy = UUID(0L, 11L)
        val rec = GatewayRecorder()
        rec.victims = listOf(HitCandidate(enemy, Vec3(0.0, 0.0, 2.0), 0.0, HitBox(Vec3(-0.3, 0.0, 1.7), Vec3(0.3, 1.8, 2.3)), 1.62))
        val calls = play(skill, seed = 1L, events = emptyList(), ticks = 8, rec = rec)
        val fire = calls.filter { it.verb == "damage" && it.instance == enemy.toString() && "fire=12" in it.payload }
        assertThat(fire).describedAs("area damage around the bound frame, calls=%s", calls).hasSize(1)
    }

    @Test
    fun ownerlessMainHandMeshIsRefusedInTheSimLikeOnPaper() {
        val skill = ok(
            loadInline(
                """
                apiVersion: astera/v3
                kind: Skill
                id: held
                item: stone-pickaxe
                meta: {cooldown_ticks: 0, resource_cost: 0}
                machine:
                  start: s
                  states:
                    s:
                      for: 4
                      then: e
                      enter:
                        - {id: d, op: display, kind: item, item: main_hand, anchor: caster, for: 4}
                    e: {final: true}
                """.trimIndent(),
            ),
        )
        play(skill, seed = 1L, ticks = 6)
        val ownerless = GatewayRecorder().also { it.casterOnline = false }
        assertThatThrownBy { play(skill, seed = 1L, events = emptyList(), ticks = 6, rec = ownerless) }.hasMessageContaining("owner offline")
        // the mesh comes from the cast's item, so a hand that is empty (or holds anything else) cannot refuse it
        val drawn = GatewayRecorder()
        play(skill, seed = 1L, events = emptyList(), ticks = 6, rec = drawn)
        assertThat(drawn.heldMeshItems).containsExactly("stone-pickaxe")
    }

    private val lineCallee = """
        ---
        apiVersion: astera/v3
        kind: Skill
        id: thread
        meta: {cooldown_ticks: 0, resource_cost: 0}
        frames_in: {a: anchor, b: anchor}
        machine:
          start: s
          states:
            s:
              for: 1
              then: e
              enter:
                - {id: ln, op: ribbon, from: a, to: b, thickness: 0.1, color: "#FFFFE070", for: 4, detach: true}
            e: {final: true}
    """.trimIndent()

    private fun lineCaller(body: String) = """
        ---
        apiVersion: astera/v3
        kind: Skill
        id: liner
        meta: {cooldown_ticks: 0, resource_cost: 0}
        anchors:
          - {id: muzzle, anchor: caster, offset: {up: 1.0, forward: 0.5}, mode: snapshot}
        machine:
          tail: 4
          start: s
          states:
            s:
              for: 6
              then: e
              ${body.lines().joinToString("\n              ")}
            e: {final: true}
    """.trimIndent()

    private fun lines(calls: List<RecordedCall>) =
        calls.filter { it.verb == "spawn" && it.payload == "kind=ribbon" && it.detail.contains("from=") }.map { it.detail }

    private fun end(detail: String, key: String): Vec3 =
        Regex(key + """=(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+)""").find(detail)!!.groupValues.let { Vec3(it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) }

    private fun hitLine(handlerRow: String, extra: String): String {
        val caller = lineCaller(
            """
              do:
                - id: sel
                  op: hit
                  at: 0
                  anchor: caster
                  shape: {sphere: 8.0}
                  targets: enemies
                  ignore_invulnerability: false
                  damage: {physical: 1.0}
                  on:
                    hit:
                      - $handlerRow
            """.trimIndent(),
        )
        val skill = ok(loadInline(caller + extra))
        val rec = GatewayRecorder()
        rec.victims = listOf(HitCandidate(UUID(0L, 11L), Vec3(0.0, 0.0, 2.0), 0.0, HitBox(Vec3(-0.3, 0.0, 1.7), Vec3(0.3, 1.8, 2.3)), 1.62))
        return lines(play(skill, seed = 1L, events = emptyList(), ticks = 8, rec = rec)).single()
    }

    @Test
    fun calleeLineRibbonDrawsBetweenTheCallersAnchorsOnHit() {
        val viaCallee = hitLine("{id: th, use: skill:thread, frames: {a: muzzle, b: contact}}", "\n" + lineCallee)
        val direct = hitLine("{id: th, op: ribbon, from: muzzle, to: contact, thickness: 0.1, color: \"#FFFFE070\", for: 4, detach: true}", "")
        assertThat(end(viaCallee, "from").y).describedAs(viaCallee).isEqualTo(2.0, org.assertj.core.data.Offset.offset(0.01))
        // the callee's `frames_in` ends are the caller's anchors: the very line the same ribbon draws when written in the caller
        assertThat(viaCallee).isEqualTo(direct)
    }

    @Test
    fun calleeLineRibbonDrawsBetweenTheCallersAnchorOnAMissToo() {
        val caller = lineCaller(
            """
              enter:
                - {id: th, use: skill:thread, frames: {a: muzzle, b: caster}}
            """.trimIndent(),
        )
        val skill = ok(loadInline(caller + "\n" + lineCallee))
        val found = lines(play(skill, seed = 1L, ticks = 8))
        assertThat(found).hasSize(1)
        assertThat(end(found.single(), "from").y).describedAs(found.single()).isEqualTo(2.0, org.assertj.core.data.Offset.offset(0.01))
    }
}
