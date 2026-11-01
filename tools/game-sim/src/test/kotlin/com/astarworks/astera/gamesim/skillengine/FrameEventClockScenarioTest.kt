package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Frame grammar gaps G1 and G2 (ADR-0314), through the real TablePlayer:
 *
 *  - G1: a frame born by an event (`on: event.*`) or by a state entry (`on: state.<name>`) counts its `move:` / `rotate:` keys from that tick, not from
 *    the cast start and not from the first row that reads it. The same skill with the event at two different ticks plays the same keys from each;
 *  - G2: `origin: attacker[.point]` roots a frame on the entity that hit the caster (`on: event.attacker`, one instance per attacker, or under `on: state.<name>`);
 *  - an unknown frame keyword, an unknown state, an attacker origin outside its event all fail at load with the frame named.
 */
class FrameEventClockScenarioTest : SkillEngineScenario() {
    private fun compiled(yaml: String): CompiledSkill = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> r.value
        is Result.Failure -> throw AssertionError("expected to load: ${r.error}")
    }

    private fun failure(yaml: String): String = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> throw AssertionError("expected a load error")
        is Result.Failure -> r.error.toString()
    }

    private val attacker = UUID(0L, 77L)
    private val attackerAt = Vec3(3.0, 0.0, 2.0)

    private fun attackerCandidate() =
        HitCandidate(attacker, attackerAt, 0.0, HitBox(Vec3(2.7, 0.0, 1.7), Vec3(3.3, 1.8, 2.3)), 1.62)

    private fun skill(id: String, frame: String, rowAt: Int): CompiledSkill = compiled(
        """
        apiVersion: astera/v3
        kind: Skill
        id: $id
        frames:
          - $frame
        machine:
          start: guard
          states:
            guard:
              for: 30
              then: done
              on:
                - {event: hurt, to: riposte}
            riposte:
              for: 20
              then: done
              do:
                - {id: p, op: particle, particle: flame, frame: sweep, pos: {v: 0.0}, count: 1, at: $rowAt, every: 1, for: 8}
            done: {final: true}
        """,
    )

    private fun run(skill: CompiledSkill, eventTick: Int): Map<Int, Vec3> {
        val rec = GatewayRecorder()
        rec.victimMotion = { listOf(attackerCandidate()) }
        play(skill, 1L, listOf(RuntimeEvent(EventPath("hurt"), eventTick, null, null, null, attacker)), eventTick + 24, rec)
        return rec.emittedAt.filter { it.second == "flame" }.associate { it.first to it.third }
    }

    @Test
    fun aStateFrameCountsItsKeysFromTheStateEntryWhereverTheEntryFalls() {
        val sweep = skill("state-clock", "{id: sweep, on: state.riposte, origin: caster, v: forward, w: up, s: v x w, move: [{at: 0, for: 10, to: {v: 2.0}}]}", 0)
        val early = run(sweep, 3)
        val late = run(sweep, 11)
        assertThat(early).isNotEmpty()
        val e0 = early.keys.min()
        val l0 = late.keys.min()
        assertThat(l0 - e0).describedAs("the late entry starts the same row 8 ticks later").isEqualTo(8)
        for (k in 0..7) {
            val a = early.getValue(e0 + k)
            val b = late.getValue(l0 + k)
            assertThat(a.distance(b)).describedAs("entry+$k: the frame pose is the same whenever the state was entered").isLessThan(1e-9)
        }
        // by hand: move v 2.0 over 10 ticks from the entry; at entry it has not moved
        assertThat(early.getValue(e0 + 5).z - early.getValue(e0).z).describedAs("half-way is 1.0 forward").isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6))
    }

    @Test
    fun anEventFrameCountsItsKeysFromTheEventTickNotFromItsFirstRead() {
        // the reading row starts 3 ticks into the state: the frame was born on the event, so its first read is already 3 ticks into its move
        val sting = skill("event-clock", "{id: sweep, on: event.attacker, each: true, origin: attacker.feet, v: forward, w: up, s: v x w, move: [{at: 0, for: 10, to: {v: 2.0}}]}", 3)
        for (eventTick in listOf(2, 9)) {
            val out = run(sting, eventTick)
            val first = out.keys.min()
            val z = out.getValue(first).z - attackerAt.z
            val sinceEvent = first - eventTick
            assertThat(sinceEvent).describedAs("the row reads 3 ticks after the event").isEqualTo(3)
            assertThat(z).describedAs("event at $eventTick: first read is $sinceEvent ticks into the move (0.2 per tick)").isEqualTo(0.2 * sinceEvent, org.assertj.core.data.Offset.offset(1e-6))
        }
    }

    @Test
    fun anAttackerOriginRootsTheFrameOnTheEntityThatHitTheCaster() {
        val sting = skill("attacker-origin", "{id: sweep, on: event.attacker, each: true, origin: attacker.feet, v: forward, w: up, s: v x w}", 0)
        val out = run(sting, 4)
        assertThat(out).isNotEmpty()
        for ((tick, at) in out) {
            assertThat(at.distance(attackerAt)).describedAs("tick $tick: pos v 0 is the attacker's feet").isLessThan(1e-6)
        }
        // the default point is the attacker's centre
        val centre = skill("attacker-centre", "{id: sweep, on: event.attacker, each: true, v: forward, w: up, s: v x w}", 0)
        for ((tick, at) in run(centre, 4)) {
            assertThat(at.x).describedAs("tick $tick: x").isEqualTo(attackerAt.x, org.assertj.core.data.Offset.offset(1e-6))
            assertThat(at.y).describedAs("tick $tick: above the feet").isGreaterThan(0.5)
        }
    }

    @Test
    fun aStateFrameMayRootOnTheAttackerOfTheEventThatEnteredIt() {
        val sweep = skill("state-attacker", "{id: sweep, on: state.riposte, origin: attacker.feet, v: forward, w: up, s: v x w, move: [{at: 0, for: 10, to: {v: 2.0}}]}", 0)
        val out = run(sweep, 5)
        val e0 = out.keys.min()
        assertThat(out.getValue(e0).distance(attackerAt)).isLessThan(1e-6)
        assertThat(out.getValue(e0 + 5).z - attackerAt.z).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6))
    }

    @Test
    fun anUnknownFrameKeywordAndTheNewWordsFailLoudlyAtLoad() {
        fun load(frame: String) = failure(
            """
            apiVersion: astera/v3
            kind: Skill
            id: bad-frame
            frames:
              - $frame
            machine:
              start: s
              states:
                s:
                  for: 2
                  then: end
                  do:
                    - {id: p, op: particle, particle: flame, frame: sweep, pos: {v: 0.0}, count: 1, at: 0}
                end: {final: true}
            """,
        )
        assertThat(load("{id: sweep, origin: caster, v: forward, w: up, s: v x w, clock: event}")).contains("clock")
        assertThat(load("{id: sweep, on: state.nowhere, origin: caster, v: forward, w: up, s: v x w}")).contains("nowhere")
        assertThat(load("{id: sweep, origin: attacker, v: forward, w: up, s: v x w}")).contains("attacker")
        assertThat(load("{id: sweep, on: state.s, each: true, origin: caster, v: forward, w: up, s: v x w}")).contains("each")
        assertThat(load("{id: sweep, on: event.bogus, each: true, origin: caster, v: forward, w: up, s: v x w}")).contains("event.attacker")
    }
}
