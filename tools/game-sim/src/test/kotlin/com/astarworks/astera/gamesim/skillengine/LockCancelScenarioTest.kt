package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W1-12 acceptance (ADR-0303 10.6, A7 / A4): `lock` window and `cancel` scopes. The breakdown names these cases under
 * PartMachineWiredScenarioTest / CompositionScenarioTest (owned by W1-16 / W1-14); they live here, in the owner's own file, so the
 * package compiles alone. The manager may move them at merge. Needs the wave 1b harness (W1-20) and the compiler to run.
 */
class LockCancelScenarioTest : SkillEngineScenario() {
    private fun skill(body: String, extraStates: String = ""): String = """
        apiVersion: astera/v3
        kind: Skill
        id: lock-cancel-test
        machine:
          start: cast
          states:
            cast:
              for: 20
              then: done
              do:
        $body
        $extraStates
            done: {final: true}
    """.trimIndent()

    private fun compile(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun reject(yaml: String): SkillDecodeError = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error
    }

    @Test
    fun lockMovePitchWindow() {
        val s = compile(skill("    - {id: plant, op: lock, move: true, pitch: true, at: 0, for: 12}"))
        val rows = s.table.states.single { it.state == StatePath("cast") }.rows
        val lock = rows.single { it.action is Action.Lock }
        assertThat(lock.tick).isEqualTo(0)
        assertThat(lock.action).isEqualTo(Action.Lock(move = true, pitch = true, yaw = false, untilTick = 12))
        val end = rows.single { it.action is Action.End && it.instance == lock.instance }
        assertThat(end.tick).isEqualTo(12)

        // played: the lock gateway call appears at tick 0, the release at tick 12
        val calls = play(s, seed = 1L, ticks = 20)
        assertThat(calls.filter { it.verb.contains("lock", ignoreCase = true) }.map { it.tick }).contains(0)

        // a lock with no flag, or without a window, is a load error
        assertThat(reject(skill("    - {id: noop, op: lock, at: 0, for: 4}"))).isInstanceOf(SkillDecodeError.Invalid::class.java)
        assertThat(reject(skill("    - {id: open, op: lock, move: true, at: 0}"))).isInstanceOf(SkillDecodeError.Invalid::class.java)
    }

    @Test
    fun cancelCastEmitsCancelThenEnd() {
        val s = compile(skill("    - {id: stop, op: cancel, scope: cast, at: 3}"))
        val row = s.table.states.single { it.state == StatePath("cast") }.rows.single { it.action is Action.Cancel }
        assertThat(row.action).isEqualTo(Action.Cancel(CancelScope.Cast))
        val sub = s.table.subTables.getValue(EventPath("cast.cancel[cast]"))
        assertThat(sub.rows.map { it.action }).containsExactly(
            Action.Exit(StatePath("cast"), ExitReason.CANCEL),
            Action.Emit(EventPath("cancel")),
            Action.Emit(EventPath("end")),
        )
        assertThat(sub.rows.map { it.tick }.distinct()).containsExactly(0)
    }

    @Test
    fun cancelStateTakesThen() {
        val s = compile(skill("    - {id: leave, op: cancel, scope: state, at: 2}"))
        val sub = s.table.subTables.getValue(EventPath("cast.cancel[state]"))
        assertThat(sub.rows.map { it.action }).containsExactly(Action.Emit(EventPath("cancel")))
        // no on: cancel handler is written, so the state is left through its `then`, as by a timer but with exit.reason = cancel
        val calls = play(s, seed = 1L, ticks = 10)
        assertThat(calls.map { it.verb }).doesNotContain("end")
    }

    @Test
    fun cancelInstanceFiresEnd() {
        val s = compile(
            skill(
                """
                |    - {id: marker, op: display, kind: item, item: fire_charge, context: fixed, anchor: caster, at: 0, for: 20}
                |    - {id: drop, op: cancel, scope: marker, at: 4}
                """.trimMargin().prependIndent(""),
            ),
        )
        val sub = s.table.subTables.values.single { it.event.value.startsWith("cast.cancel[marker") }
        assertThat(sub.rows.single().action).isInstanceOf(Action.End::class.java)
        val calls = play(s, seed = 1L, ticks = 10)
        assertThat(calls.filter { it.instance.startsWith("marker") && it.verb.contains("end", ignoreCase = true) }.map { it.tick }).containsExactly(4)
    }

    @Test
    fun cancelDepthFirstRunsExits() {
        val s = compile(
            """
            apiVersion: astera/v3
            kind: Skill
            id: lock-cancel-nested
            machine:
              start: outer
              states:
                outer:
                  machine:
                    start: inner
                    states:
                      inner:
                        for: 10
                        do:
                          - {id: stop, op: cancel, scope: cast, at: 2}
                done: {final: true}
            """.trimIndent(),
        )
        val sub = s.table.subTables.values.single { it.event.value.endsWith("cancel[cast]") }
        val exits = sub.rows.map { it.action }.filterIsInstance<Action.Exit>()
        // innermost state first, each with reason cancel
        assertThat(exits.map { it.state.value }).containsExactly("outer/inner", "outer")
        assertThat(exits.map { it.reason }.distinct()).containsExactly(ExitReason.CANCEL)
    }
}
