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
    fun partInlinedWithPrefixedIds() {
        val r = load("with: {window: 10, power: 2}\n      frames: {src: blade}")
        assertThat(r).isInstanceOf(Result.Success::class.java)
        val dump = (r as Result.Success).value.toString()
        assertThat(dump).contains("p/charge").contains("p/charge/mark")
    }

    @Test
    fun partSeesOnlyBoundFrames() {
        val r = load("with: {window: 10, power: 2}\n      frames: {src: nowhere}")
        val e = (r as Result.Failure).error
        assertThat(e).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
    }

    @Test
    fun partEmitsCrossBoundary() {
        val bad = loadInline(part + "\n" + skill("with: {window: 10, power: 2}\n      frames: {src: blade}").replace("p.end", "p.nope"))
        assertThat((bad as Result.Failure).error).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
        val ok = load("with: {window: 10, power: 2}\n      frames: {src: blade}")
        assertThat(ok).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun withArgsAreTypedAndRequired() {
        val missing = load("with: {window: 10}\n      frames: {src: blade}")
        assertThat((missing as Result.Failure).error).isInstanceOf(SkillDecodeError.MissingField::class.java)
        val typed = load("with: {window: 10, power: high}\n      frames: {src: blade}")
        assertThat((typed as Result.Failure).error).isInstanceOf(SkillDecodeError.TypeMismatch::class.java)
        val unknown = load("with: {window: 10, power: 2, extra: 1}\n      frames: {src: blade}")
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
}
