package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ComposeError
import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.compose.MachineTables
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.compose.SectionLowerer
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.LockEnd
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * W1b-02c acceptance (ADR-0303 10.2 rev4.2, wave1-resolutions items 7 and 14): sub-table keys are exactly `<state>.exit[<reason>]` and
 * `<state>.on[<k>]`, no bubbling, parallel regions, lock rows relative to the state base. Runs at the wave 1b gate.
 */
class MachineTablesScenarioTest : SkillEngineScenario() {
    private fun keys(s: CompiledSkill): Set<String> = s.table.subTables.keys.map { it.value }.toSet()

    private fun compiled(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    @Test
    fun exampleEKeySet() {
        // guard is a part call (no parent state after flattening): parry_window leaves by hurt and by timer(30); riposte / whiff / end by timer.
        val keys = keys(loadExample("e-couture-rapier-def-parry-part")).filter { it.contains(".exit[") || it.contains(".on[") }.toSet()
        assertThat(keys).containsExactlyInAnyOrder(
            "guard/parry_window.exit[timer]", "guard/parry_window.exit[event]", "guard/parry_window.exit[cancel]", "guard/parry_window.on[0]",
            "guard/riposte.exit[timer]", "guard/riposte.exit[cancel]",
            "guard/whiff.exit[timer]", "guard/whiff.exit[cancel]",
            "guard/end.exit[timer]", "guard/end.exit[cancel]",
        )
    }

    @Test
    fun exampleERiposteHitIsAbsentFromCancelExit() {
        val t = loadExample("e-couture-rapier-def-parry-part").table
        val timer = t.subTables.getValue(EventPath("guard/riposte.exit[timer]"))
        val cancel = t.subTables.getValue(EventPath("guard/riposte.exit[cancel]"))
        assertThat(timer.rows.first().action).isEqualTo(Action.Exit(StatePath("guard/riposte"), ExitReason.TIMER))
        assertThat(cancel.rows.first().action).isEqualTo(Action.Exit(StatePath("guard/riposte"), ExitReason.CANCEL))
        assertThat(timer.rows.any { it.action is Action.Damage }).isTrue()
        assertThat(cancel.rows.none { it.action is Action.Damage }).isTrue()
    }

    @Test
    fun exampleGKeySet() {
        val keys = keys(loadExample("g-couture-rapier-s2-states")).filter { it.contains(".exit[") || it.contains(".on[") }.toSet()
        assertThat(keys).containsExactlyInAnyOrder(
            "coin1.exit[timer]", "coin1.exit[cancel]",
            "raise.exit[timer]", "raise.exit[cancel]",
            "throw.exit[timer]", "throw.exit[event]", "throw.exit[cancel]", "throw.on[0]",
            "stuck.exit[timer]", "stuck.exit[cancel]",
            "coin3.exit[timer]", "coin3.exit[cancel]",
        )
    }

    @Test
    fun noBubblingNoHashKeys() {
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: no-bubble
            machine:
              start: p
              states:
                p:
                  for: 30
                  then: z
                  on: [ { event: stop, to: z } ]
                  start: c
                  states:
                    c: { for: 5, then: d }
                    d: { final: true }
                z: { final: true }
            """.trimIndent(),
        )
        assertThat(keys(s).none { it.contains('#') || it.contains("/p#") }).isTrue()
        // the child holds only its own branch; the ancestor's event and timer stay on the ancestor
        val child = s.table.states.first { it.state == StatePath("p/c") }
        assertThat(child.branches.map { it.event.value }).containsExactly("timer(5)")
        val parent = s.table.states.first { it.state == StatePath("p") }
        assertThat(parent.branches.map { it.event.value }).containsExactlyInAnyOrder("stop", "timer(30)")
        // the child can be preempted, so it has exit[preempt]; the parent has no preempt (no parent of its own)
        assertThat(keys(s)).contains("p/c.exit[preempt]", "p/c.exit[timer]", "p/c.exit[cancel]", "p.exit[event]", "p.exit[timer]")
        assertThat(keys(s)).doesNotContain("p.exit[preempt]")
        // the parent enters its direct child at the entry tick
        val enters = parent.rows.filter { it.action is Action.Enter }.map { (it.action as Action.Enter).state }
        assertThat(enters).containsExactly(StatePath("p"), StatePath("p/c"))
    }

    @Test
    fun parallelRegionsAreEnteredByTheParent() {
        val noOps = SectionLowerer { _, _, _ -> Result.Failure(SkillDecodeError.Invalid("par", "x", "no ops expected")) }
        fun state(id: String, final: Boolean = false, parallel: List<Machine> = emptyList()) =
            MachineState(id, null, null, final, emptyList(), emptyList(), emptyList(), emptyList(), null, parallel)
        fun region(vararg ids: String) = Machine(ids.first(), ids.associateWith { state(it, final = true) })
        val machine = Machine(
            "par",
            mapOf("par" to state("par", parallel = listOf(region("a"), region("b"))), "z" to state("z", final = true)),
        )
        val m = (MachineTables.lower("par", machine, noOps, EngineConfig()) as Result.Success).value
        val par = m.states.first { it.state == StatePath("par") }
        assertThat(par.rows.filter { it.action is Action.Enter }.map { (it.action as Action.Enter).state })
            .containsExactly(StatePath("par"), StatePath("par/a"), StatePath("par/b"))
        // each final region state emits the parent's end event (the player counts all regions, TablePlayer.levelFinal)
        val a = m.states.first { it.state == StatePath("par/a") }
        assertThat(a.rows.map { it.action }).contains(Action.Emit(EventPath("par.end")))
        assertThat(m.subTables.keys.map { it.value }).contains("par/a.exit[preempt]", "par/b.exit[preempt]", "par/a.exit[cancel]")
    }

    @Test
    fun lockRowIsRelativeToTheStateBase() {
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: lock-base
            machine:
              start: first
              states:
                first: { for: 10, then: second }
                second:
                  for: 20
                  then: done
                  do:
                    - {id: plant, op: lock, move: all, at: 3, for: 5}
                done: { final: true }
            """.trimIndent(),
        )
        val rows = s.table.states.single { it.state == StatePath("second") }.rows
        val lock = rows.single { it.action is Action.Lock }
        // tick 3 of the state; held until tick 8 of the state, not of the cast (the state is entered at cast tick 10)
        assertThat(lock.tick).isEqualTo(3)
        assertThat((lock.action as Action.Lock).until).isEqualTo(LockEnd.AtTick(8))
    }

    // ---- W2-09: R35 patch ops, R36 nested start, R39 input events --------------------------------------------------------

    private fun failureOf(yaml: String): SkillDecodeError = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error
    }

    @Test
    fun nestedStartRequired() {
        val error = failureOf(
            """
            apiVersion: astera/v3
            kind: Skill
            id: nested-no-start
            machine:
              start: p
              states:
                p:
                  states:
                    c: { final: true }
            """.trimIndent(),
        )
        assertThat(error).isInstanceOf(SkillDecodeError.NestedStartMissing::class.java)
        assertThat((error as SkillDecodeError.NestedStartMissing).state).isEqualTo("p")
    }

    @Test
    fun startNamesChildIsLoadError() {
        val error = failureOf(
            """
            apiVersion: astera/v3
            kind: Skill
            id: start-child
            machine:
              start: p/c
              states:
                p:
                  start: c
                  states:
                    c: { final: true }
            """.trimIndent(),
        )
        assertThat(error).isInstanceOf(SkillDecodeError.StartNamesChild::class.java)
        assertThat((error as SkillDecodeError.StartNamesChild).start).isEqualTo("p/c")
    }

    private val patchBase = """
        apiVersion: astera/v3
        kind: Skill
        id: base
        machine:
          start: cast
          states:
            cast:
              for: 10
              then: done
              do:
                - { id: hit, op: damage, amount: 6 }
                - { id: spark, op: particle, count: 3 }
            done: { final: true }
    """.trimIndent()

    private fun patchDoc(vararg ops: String): String =
        "apiVersion: astera/v3\nkind: Patch\nid: tune\ntarget: base\npatchKind: balance\nops:\n" + ops.joinToString("\n") { "  $it" }

    private fun composeWith(tmp: Path, patch: String): SkillComposer {
        Files.writeString(tmp.resolve("base.yaml"), patchBase)
        Files.writeString(tmp.resolve("tune.yaml"), patch)
        return SkillComposer(tmp, ComposeOptions(allowBalancePatches = true))
    }

    private fun doRows(c: SkillComposer): List<Map<*, *>> {
        val machine = c.composed().getValue("base").tree["machine"] as Map<*, *>
        val cast = (machine["states"] as Map<*, *>)["cast"] as Map<*, *>
        return (cast["do"] as List<*>).map { it as Map<*, *> }
    }

    @Test
    fun patchDeleteRowById(@TempDir tmp: Path) {
        val c = composeWith(tmp, patchDoc("""- {path: "machine.states.cast.do[hit]", ${'$'}patch: delete}"""))
        assertThat(c.composeAll()).isInstanceOf(Result.Success::class.java)
        assertThat(doRows(c).map { it["id"] }).containsExactly("spark")
    }

    @Test
    fun patchReplaceField(@TempDir tmp: Path) {
        val c = composeWith(tmp, patchDoc("""- {path: "machine.states.cast.do[hit].amount", ${'$'}patch: replace, value: 9}"""))
        assertThat(c.composeAll()).isInstanceOf(Result.Success::class.java)
        val rows = doRows(c)
        assertThat(rows.map { it["id"] }).containsExactly("hit", "spark")
        assertThat(rows.first()["amount"]).isEqualTo(9)
    }

    @Test
    fun patchUnresolvedPathIsLoadError(@TempDir tmp: Path) {
        val c = composeWith(tmp, patchDoc("""- {path: "machine.states.cast.do[nope].amount", ${'$'}patch: replace, value: 9}"""))
        val errors = (c.composeAll() as Result.Failure).error
        assertThat(errors.single()).isInstanceOf(ComposeError.PatchError.UnresolvedPath::class.java)
        assertThat((errors.single() as ComposeError.PatchError.UnresolvedPath).path).isEqualTo("machine.states.cast.do[nope].amount")
    }

    @Test
    fun inputReleaseFiresOnSkillReleased() {
        // R39: Paper raises SkillReleased as `input.release` (SkillEnginePaperRuntime); a state waiting on that name is a timed event exit.
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: hold-release
            machine:
              start: hold
              states:
                hold:
                  for: 40
                  then: fire
                  on: [ { event: input.release, to: fire } ]
                fire: { final: true }
            """.trimIndent(),
        )
        val hold = s.table.states.first { it.state == StatePath("hold") }
        assertThat(hold.branches.map { it.event.value }).containsExactlyInAnyOrder("input.release", "timer(40)")
        assertThat(keys(s)).contains("hold.exit[event]", "hold.exit[timer]")
    }

    @Test
    fun machineWorldRowsAreMachineRootRows() {
        // R45 (ADR 10.1): `machine.world` rows belong to the cast, not to a state: they land in ExpansionTable.worldRows, in no state's rows.
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: world-body
            machine:
              start: cast
              world:
                - {id: body, op: damage, amount: 3}
              states:
                cast:
                  for: 34
                  then: done
                done: { final: true }
            """.trimIndent(),
        )
        val world = s.table.worldRows.filter { it.action is Action.Damage }
        assertThat(world).hasSize(1)
        assertThat(world.first().tick).isEqualTo(0)
        assertThat(world.first().src.value).contains("world")
        assertThat(s.table.states.flatMap { it.rows }.none { it.action is Action.Damage }).`as`("no state carries the world row").isTrue()
    }

    @Test
    fun machineWorldBodyOutlivesItsStartStateWithoutOverrun() {
        // couture s2: the body clip starts at 63, after the 34-tick start state; the bound is the machine's worst-case path (34 + 40), not the start state.
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: world-body-late
            machine:
              start: cast
              world:
                - {id: clip, op: body, at: 63, anim: slash_a, speed: 1.0, for: 9}
              states:
                cast:
                  for: 34
                  then: tail
                tail:
                  for: 40
                  then: done
                done: { final: true }
            """.trimIndent(),
        )
        assertThat(s.table.worldRows.map { it.tick }).containsExactly(63, 72)
        val calls = play(s, 1L).filter { it.verb == "effect" }
        assertThat(calls.map { it.tick to it.payload }).contains(63 to "body=slash_a speed=1", 72 to "body_stop=slash_a")
    }

    @Test
    fun childFinalEndChainFitsTheSameTickCascade() {
        // B6: timer(1) -> strike leaves -> recover (child final) emits cast.end -> cast handles end -> done (top-level final) ends the machine.
        // Three waves in one tick: the table must carry that depth so the player's cap (depth + 1 waves) does not cut the chain.
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: end-chain
            machine:
              start: cast
              states:
                cast:
                  start: strike
                  do:
                    - {op: sound, sound: "astera:test/ping", anchor: caster, at: 0}
                  on: [ { event: end, to: done } ]
                  states:
                    strike: { for: 1, then: recover }
                    recover: { final: true }
                done: { final: true }
            """.trimIndent(),
        )
        assertThat(s.table.bounds.sameTickDepth).isGreaterThanOrEqualTo(2)
        val calls = play(s, 1L)
        assertThat(calls.filter { it.verb == "sound" }.map { it.tick }).containsExactly(0)
        assertThat(calls.none { it.verb == "fault" || it.payload.contains("cascade") }).isTrue()
        // the machine ended on the v2 tick: the done level was entered at tick 1 and nothing is left running
        assertThat(s.table.states.first { it.state == StatePath("cast/recover") }.duration).isEqualTo(Duration.Fixed(0))
    }

    @Test
    fun finalStateLastsZeroTicksSoAFullLengthPathIntoItFits() {
        // B5: entering a final ends the machine (ADR-0303 10.1), so it adds no 0..max_cast_ticks span to the path
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: long-then-final
            machine:
              start: a
              states:
                a: { for: 1190, then: z }
                z: { final: true }
            """.trimIndent(),
        )
        assertThat(s.table.states.first { it.state == StatePath("z") }.duration).isEqualTo(Duration.Fixed(0))
        assertThat(s.table.bounds.path.max).isEqualTo(1190)
    }

    @Test
    fun timerAndInputBranchesAndExitTablesPassTheStaticChecks() {
        // B3 + B4: `timer(n)` and `input.*` are engine events (never emitted by an op); `.exit[...]` tables are delivered by leaving the state
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: engine-events
            machine:
              start: p
              states:
                p:
                  for: 30
                  then: z
                  on: [ { event: input.use, to: z } ]
                  start: c
                  states:
                    c: { for: 5, then: d }
                    d: { final: true }
                z: { final: true }
            """.trimIndent(),
        )
        assertThat(keys(s)).contains("p.exit[event]", "p.exit[timer]", "p/c.exit[timer]")
    }
}
