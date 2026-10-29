package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.LockEnd
import com.astarworks.astera.core.domain.skillengine.table.LockMove
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W1-12 acceptance (ADR-0303 10.6, A7 / A4): `lock` window and `cancel` scopes. The breakdown names these cases under
 * PartMachineWiredScenarioTest / CompositionScenarioTest (owned by W1-16 / W1-14); they live here, in the owner's own file, so the
 * package compiles alone. The manager may move them at merge. Needs the wave 1b harness (W1-20) and the compiler to run.
 */
class LockCancelScenarioTest : SkillEngineScenario() {
    /** One state `cast` (20 ticks, `then: done`) whose `do:` holds [body] (list items, any indent), plus optional extra states. */
    private fun skill(body: String, extraStates: String = ""): String {
        val doLines = body.trimIndent().lines().joinToString("\n") { "        $it" }
        val extra = if (extraStates.isBlank()) "" else extraStates.trimIndent().lines().joinToString("\n", prefix = "\n") { "    $it" }
        return """
            |apiVersion: astera/v3
            |kind: Skill
            |id: lock-cancel-test
            |meta: {cooldown_ticks: 0, resource_cost: 0}
            |machine:
            |  start: cast
            |  states:
            |    cast:
            |      for: 20
            |      then: done
            |      do:
            |$doLines$extra
            |    done: {final: true}
        """.trimMargin()
    }

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
        val s = compile(skill("- {id: plant, op: lock, move: all, pitch: true, at: 0, for: 12}"))
        val rows = s.table.states.single { it.state == StatePath("cast") }.rows
        val lock = rows.single { it.action is Action.Lock }
        assertThat(lock.tick).isEqualTo(0)
        assertThat(lock.action).isEqualTo(Action.Lock(move = LockMove.ALL, pitch = true, yaw = false, until = LockEnd.AtTick(12)))
        val end = rows.single { it.action is Action.End && it.instance == lock.instance }
        assertThat(end.tick).isEqualTo(12)

        // played: the lock gateway call appears at tick 0, the release at tick 12
        val calls = play(s, seed = 1L, ticks = 20)
        assertThat(calls.filter { it.verb.contains("lock", ignoreCase = true) }.map { it.tick }).contains(0)

        // a lock with no flag, or without a window, is a load error
        assertThat(reject(skill("- {id: noop, op: lock, at: 0, for: 4}"))).isInstanceOf(SkillDecodeError.Invalid::class.java)
        assertThat(reject(skill("- {id: open, op: lock, move: all, at: 0}"))).isInstanceOf(SkillDecodeError.Invalid::class.java)
    }

    /** Founder 10/28: the look lock is a lock field next to `move`; the player hands it to `lockLook` / `unlockLook` at the window ticks. */
    @Test
    fun lookLockReachesTheLookPortOverItsWindowAndMoveOnlyDoesNot() {
        val look = compile(skill("- {id: look, op: lock, pitch: true, at: 2, for: 6}"))
        val calls = play(look, seed = 1L, ticks = 12)
        assertThat(calls.filter { it.verb == "lock" }.map { it.tick to it.payload }).containsExactly(2 to "pitch=true yaw=false until=8")
        assertThat(calls.filter { it.verb == "unlock" }.map { it.tick }).containsExactly(8)

        val both = compile(skill("- {id: look, op: lock, move: walk, pitch: true, yaw: true, at: 0, for: 4}"))
        assertThat(play(both, seed = 1L, ticks = 8).filter { it.verb == "lock" }.map { it.payload }).containsExactly("pitch=true yaw=true until=4")

        // move alone never touches the look port
        val move = compile(skill("- {id: plant, op: lock, move: all, at: 0, for: 4}"))
        assertThat(play(move, seed = 1L, ticks = 8).filter { it.verb == "lock" || it.verb == "unlock" }).isEmpty()
    }

    @Test
    fun cancelOfAnUnresolvedNameIsANamedLoadError() {
        // ADR-0303 line 817: an unresolved cancel name is a load error, named after the cancel row and the name
        val e = reject(skill("- {id: g, at: 4, do: [{id: drop, op: cancel, scope: nothing}]}"))
        assertThat(e).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
        assertThat((e as SkillDecodeError.UndefinedReference).ref).contains("nothing")
    }

}
