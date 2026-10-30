package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0303 rev4.13 guarantees that were only covered indirectly:
 *  - a `status` op on a victim this cast's own damage already killed is named in the trace (`status_skip <victim> dead_victim`), never silent,
 *    and a surviving victim of the same hit still gets the status;
 *  - the main-hand mesh of a rig draws the item the cast was STARTED with (CastRef.heldItem, fixed at cast start) for the whole cast, a
 *    second rig spawned after the hand moved included, and `hand: off` draws the off-hand part's own model in THIRDPERSON_LEFTHAND.
 */
class TraceGapsScenarioTest : SkillEngineScenario() {
    private val dead = UUID(0L, 21L)
    private val alive = UUID(0L, 22L)

    private fun load(yaml: String) = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun candidate(id: UUID, x: Double) = HitCandidate(id, Vec3(x, 0.0, 2.0), 0.0, HitBox(Vec3(x - 0.3, 0.0, 1.7), Vec3(x + 0.3, 1.8, 2.3)), 1.62)

    @Test
    fun statusOnAVictimThisCastKilledIsTracedAndTheSurvivorStillGetsIt() {
        val skill = load(
            """
            apiVersion: astera/v3
            kind: Skill
            id: status-skip-trace
            machine:
              start: s
              states:
                s:
                  for: 6
                  do:
                    - id: sel
                      op: hit
                      at: 0
                      anchor: caster
                      shape: {sphere: 8.0}
                      targets: enemies
                      ignore_invulnerability: false
                      damage: {physical: 5.0}
                      on:
                        hit:
                          - {id: mark, op: status, target: event.victim, status: bleed, stacks: 1, for: 40}
            """,
        )
        val rec = GatewayRecorder()
        rec.victims = listOf(candidate(dead, 0.0), candidate(alive, 1.0))
        rec.killOnDamage += dead
        playProbe(skill, ticks = 6, rec = rec)

        val skips = rec.calls.filter { it.verb == "status_skip" }
        assertThat(skips.map { "${it.instance} ${it.payload}" }).containsExactly("$dead dead_victim")
        val applied = rec.calls.filter { it.verb == "status" }
        assertThat(applied.map { it.instance }).containsExactly(alive.toString())
        assertThat(rec.calls.filter { it.verb == "damage" }.map { it.instance }).containsExactlyInAnyOrder(dead.toString(), alive.toString())
    }

    @Test
    fun withoutAKillNoSkipIsTraced() {
        val skill = load(
            """
            apiVersion: astera/v3
            kind: Skill
            id: status-no-skip
            machine:
              start: s
              states:
                s:
                  for: 6
                  do:
                    - id: sel
                      op: hit
                      at: 0
                      anchor: caster
                      shape: {sphere: 8.0}
                      targets: enemies
                      ignore_invulnerability: false
                      damage: {physical: 5.0}
                      on:
                        hit:
                          - {id: mark, op: status, target: event.victim, status: bleed, stacks: 1, for: 40}
            """,
        )
        val rec = GatewayRecorder()
        rec.victims = listOf(candidate(dead, 0.0))
        playProbe(skill, ticks = 6, rec = rec)
        assertThat(rec.calls.filter { it.verb == "status_skip" }).isEmpty()
        assertThat(rec.calls.filter { it.verb == "status" }).hasSize(1)
    }

    @Test
    fun heldMeshStaysOnTheCastStartItemAndOffHandUsesTheLeftHandPart() {
        val skill = load(
            """
            apiVersion: astera/v3
            kind: Skill
            id: held-mesh-fixed
            item: twin-daggers
            machine:
              start: first
              tail: 6
              states:
                first:
                  for: 6
                  then: second
                  do:
                    - id: rig_main
                      op: rig
                      parent: caster
                      mount: caster.hand_r
                      rest: [{axis: left, deg: 72}]
                      for: 5
                      detach: true
                      swing:
                        - {at: 0, for: 5, from: {left: 0.1, up: 0.5, forward: 0.8}, to: {left: 0.8, up: 0.3, forward: 0.4}, edge: leading, ease: out_cubic}
                    - id: rig_off
                      op: rig
                      parent: caster
                      hand: off
                      mount: caster.hand_l
                      rest: [{axis: left, deg: 72}]
                      for: 5
                      detach: true
                      swing:
                        - {at: 0, for: 5, from: {left: -0.1, up: 0.5, forward: 0.8}, to: {left: -0.8, up: 0.3, forward: 0.4}, edge: leading, ease: out_cubic}
                second:
                  for: 6
                  do:
                    - id: rig_late
                      op: rig
                      parent: caster
                      mount: caster.hand_r
                      rest: [{axis: left, deg: 72}]
                      for: 5
                      detach: true
                      swing:
                        - {at: 0, for: 5, from: {left: 0.1, up: 0.5, forward: 0.8}, to: {left: 0.8, up: 0.3, forward: 0.4}, edge: leading, ease: out_cubic}
            """,
        )
        val rec = GatewayRecorder()
        // the cast was started holding knight-sword (the triggering item); the skill is bound to twin-daggers. The hand moves on after tick 0.
        playProbe(skill, ticks = 20, rec = rec, heldItem = "knight-sword")

        // main-hand meshes: the early rig (tick 0) and the late rig (tick 6, after the hand moved) both draw the cast-start item
        assertThat(rec.heldMeshItems).containsExactly("knight-sword", "knight-sword")
        val spawns = rec.calls.filter { it.verb == "spawn" && it.payload == "kind=item" }
        assertThat(spawns.map { it.tick }).containsExactly(0, 0, 6)
        // hand: off draws the off-hand part's own model in the left-hand transform, never the main-hand stack
        val modes = spawns.map { Regex("mode=(\\w+)").find(it.detail)!!.groupValues[1] }
        assertThat(modes.count { it == "THIRDPERSON_LEFTHAND" }).isEqualTo(1)
        val off = spawns.single { "THIRDPERSON_LEFTHAND" in it.detail }
        assertThat(off.detail).contains("item=astera:twin-daggers-offhand")
        assertThat(spawns.filter { it !== off }.map { it.detail }).allSatisfy { assertThat(it).doesNotContain("THIRDPERSON_LEFTHAND") }
    }
}
