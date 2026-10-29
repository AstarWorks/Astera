package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 section 9 (W1-14): state-level part calls. Runs at the wave 1b gate, once SkillCompiler / loadInline exist. */
class CompositionScenarioTest : SkillEngineScenario() {
    private val part = """
        ---
        apiVersion: astera/v3
        kind: Part
        id: pulse
        params:
          window: {type: ticks, required: true}
          power: {type: number, required: true}
        frames_in: {src: anchor}
        emits: [hit, end]
        machine:
          start: charge
          states:
            charge:
              for: "= window"
              then: done
              do: [{id: mark, op: sound, sound: block.note_block.bell, anchor: src, volume: 0.5, pitch: 1.0}]
            done: {final: true}
    """.trimIndent()

    private fun skill(call: String, extra: String = "") = """
        ---
        apiVersion: astera/v3
        kind: Skill
        id: caller
        meta: {cooldown_ticks: 20, resource_cost: 0}
        anchors:
          - {id: blade, anchor: caster.hand_r, mount: caster}
        machine:
          start: p
          states:
            p:
              use: pulse
              $call
              on:
                - {event: p.end, to: fin}
            fin: {final: true}
    """.trimIndent() + extra

    private fun load(call: String) = loadInline(part + "\n" + skill(call))

    @Test
    fun withArgsAreTypedAndRequired() {
        val missing = load("with: {window: 10}\n              frames: {src: caster}")
        assertThat((missing as Result.Failure).error).isInstanceOf(SkillDecodeError.MissingField::class.java)
        val typed = load("with: {window: 10, power: high}\n              frames: {src: caster}")
        assertThat((typed as Result.Failure).error).isInstanceOf(SkillDecodeError.TypeMismatch::class.java)
        val unknown = load("with: {window: 10, power: 2, extra: 1}\n              frames: {src: caster}")
        assertThat((unknown as Result.Failure).error).isInstanceOf(SkillDecodeError.UnknownKey::class.java)
    }

    @Test
    fun recursionIsLoadError() {
        val a = """
            ---
            apiVersion: astera/v3
            kind: Part
            id: ping
            emits: [end]
            machine:
              start: s
              states:
                s: {use: pong, then: e}
                e: {final: true}
            ---
            apiVersion: astera/v3
            kind: Part
            id: pong
            emits: [end]
            machine:
              start: s
              states:
                s: {use: ping, then: e}
                e: {final: true}
            ---
            apiVersion: astera/v3
            kind: Skill
            id: loop
            meta: {cooldown_ticks: 20, resource_cost: 0}
            machine:
              start: s
              states:
                s: {use: ping, then: e}
                e: {final: true}
        """.trimIndent()
        val r = loadInline(a)
        val e = (r as Result.Failure).error
        assertThat(e.message).contains("cycle")
    }

    private val sub = """
        ---
        apiVersion: astera/v3
        kind: Skill
        id: puff
        meta: {cooldown_ticks: 40, resource_cost: 5}
        params:
          count: {type: number, required: true}
        frames_in: {origin: anchor}
        machine:
          start: s
          states:
            s:
              for: 4
              then: e
              enter: [{id: smoke, op: particle, particle: smoke, anchor: origin, count: "${'$'}{count}", offset: [0.2, 0.2, 0.2], extra: 0.01, at: 0}]
            e: {final: true}
    """.trimIndent()

    private fun callerOf(call: String) = """
        ---
        apiVersion: astera/v3
        kind: Skill
        id: caller
        meta: {cooldown_ticks: 20, resource_cost: 0}
        anchors:
          - {id: blade, anchor: caster.hand_r, mount: caster}
        machine:
          start: s
          states:
            s:
              for: 10
              then: fin
              enter:
                - $call
            fin: {final: true}
    """.trimIndent()

    private fun skillCalls(r: Result<com.astarworks.astera.core.domain.skillengine.table.CompiledSkill, SkillDecodeError>) =
        (r as Result.Success).value.table.let { t ->
            (t.states.flatMap { it.rows } + t.subTables.values.flatMap { it.rows })
                .map { it.action }
                .filterIsInstance<com.astarworks.astera.core.domain.skillengine.table.Action.Call>()
                .map { it.callee }
                .filterIsInstance<com.astarworks.astera.core.domain.skillengine.table.Callee.SkillRef>()
        }

    @Test
    fun skillCallUngatedByDefault() {
        val calls = skillCalls(loadInline(callerOf("{id: c, use: skill:puff, with: {count: 6}, frames: {origin: caster}}") + "\n" + sub))
        assertThat(calls).hasSize(1)
        assertThat(calls[0].id).isEqualTo("puff")
        assertThat(calls[0].gateCooldown).isFalse()
        assertThat(calls[0].gateCost).isFalse()
    }

    @Test
    fun skillCallGateCooldownAndCost() {
        val both = skillCalls(loadInline(callerOf("{id: c, use: skill:puff, with: {count: 6}, frames: {origin: caster}, gate: {cooldown: true, cost: true}}") + "\n" + sub))
        assertThat(both.single().gateCooldown).isTrue()
        assertThat(both.single().gateCost).isTrue()
        val costOnly = skillCalls(loadInline(callerOf("{id: c, use: skill:puff, with: {count: 6}, frames: {origin: caster}, gate: {cost: true}}") + "\n" + sub))
        assertThat(costOnly.single().gateCooldown).isFalse()
        assertThat(costOnly.single().gateCost).isTrue()
        val onPart = loadInline(part + "\n" + callerOf("{id: c, use: pulse, with: {window: 10, power: 2}, frames: {src: caster}, gate: {cost: true}}"))
        assertThat((onPart as Result.Failure).error).isInstanceOf(SkillDecodeError.Invalid::class.java)
    }
}
