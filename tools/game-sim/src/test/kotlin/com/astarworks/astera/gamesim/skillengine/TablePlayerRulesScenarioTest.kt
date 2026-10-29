package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.CastTrace
import com.astarworks.astera.core.application.skillengine.runtime.player.LiveValueSource
import com.astarworks.astera.core.application.skillengine.runtime.player.PlayerHooks
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Branch
import com.astarworks.astera.core.domain.skillengine.table.Bounds
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import com.astarworks.astera.core.domain.skillengine.table.HitShape
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Pose
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import org.junit.jupiter.api.Test

/**
 * W1b-02d: the rules of the table player (ADR-0303 item 7 paste order, 14.3 locks, 14.7 sweep, 15c set rows, 17 trace).
 * Hand-built tables on the real [TablePlayer] and [GatewayRecorder]; the compiler is not involved.
 */
class TablePlayerRulesScenarioTest {
    // ---- table builders --------------------------------------------------------------------------------------------

    private fun id(op: String, i: Int? = null) = InstanceId(listOf(InstanceId.Segment(OpId(op), i)))
    private fun sp(s: String) = StatePath(s)
    private fun ev(s: String) = EventPath(s)
    private val none = id("none")
    private fun row(tick: Int, action: Action, inst: InstanceId = none) = Row(tick, inst, action, SrcPath("t"))
    private fun enter(state: String) = row(0, Action.Enter(sp(state)))

    private fun st(
        state: String,
        rows: List<Row> = listOf(enter(state)),
        branches: List<Branch> = emptyList(),
        final: Boolean = false,
        then: String? = null,
    ) = StateTable(sp(state), Duration.Fixed(100), then?.let(::sp), final, rows, branches)

    private fun br(event: String, target: String?, sub: String? = null, min: Int = 0, max: Int = 100) =
        Branch(ev(event), TickRange(min, max), null, target?.let(::sp), sub?.let(::ev))

    private fun sub(key: String, owner: String, vararg rows: Row) = SubTable(ev(key), sp(owner), TickRange(0, 100), 100, rows.toList(), emptyList())

    private fun sound(name: String, volume: Val<Double> = Val.Known(1.0), category: String = "master") =
        Action.Sound(name, volume, Val.Known(1.0), Val.Known(Vector3d()), category)

    private fun skill(start: String, states: List<StateTable>, subs: List<SubTable> = emptyList(), params: Map<String, Any> = emptyMap()): CompiledSkill {
        val table = ExpansionTable(
            skillId = "t", sampleSeed = 1L, params = params,
            bounds = Bounds(emptyMap(), 0, 0, 0, 0, TickRange(0, 100), 4),
            states = states, subTables = subs.associateBy { it.event }, writes = emptyMap(), conditions = emptyMap(),
            config = com.astarworks.astera.core.domain.skillengine.model.EngineConfig(),
        )
        return CompiledSkill(SkillDef("t", null, null, machine = Machine(start, emptyMap())), table)
    }

    // ---- fixture ---------------------------------------------------------------------------------------------------

    private class TraceLog : CastTrace {
        val log = ArrayList<String>()
        override fun enter(cast: CastHandle, state: StatePath, tick: Int) { log += "enter:${state.value}@$tick" }
        override fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int) { log += "exit:${state.value}:${reason.name}@$tick" }
        override fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?) { log += "event:${path.value}@$tick" }
        override fun call(cast: CastHandle, callId: InstanceId, tick: Int) { log += "call:${callId.text}@$tick" }
    }

    private class Fx(
        skill: CompiledSkill,
        val rec: GatewayRecorder = GatewayRecorder(),
        live: LiveValueSource? = null,
        query: ISkillEntityQuery? = null,
        hooks: PlayerHooks? = null,
    ) {
        val trace = TraceLog()
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, query ?: rec.query, live ?: rec.live, trace, rec.movement, rec.items, rec.world)
        val cast: CastHandle
        var now = 0

        init {
            hooks?.let { player.hooks = it }
            val request = CastRequest(skill, rec.caster, WorldId("world"), Vec3(0.0, 0.0, 0.0), 0.0, 0.0, 1L, startTick = 0)
            cast = (player.start(request, skill) as Result.Success).value
        }

        fun run(untilTick: Int, events: Map<Int, List<String>> = emptyMap()) {
            while (now <= untilTick) {
                rec.tick = now
                events[now]?.forEach { player.deliver(cast, RuntimeEvent(EventPath(it), now, null, null, null)) }
                player.tick(now)
                now++
            }
        }

        fun sounds() = rec.calls.filter { it.verb == "sound" }
    }

    // ---- item 7 / 14.8: paste order, consume rule, one transition per level per tick --------------------------------

    // ---- cancel (item 7): the player's job --------------------------------------------------------------------------

    // ---- parallel regions: the player counts the ends ---------------------------------------------------------------

    @Test
    fun parentEndIsPublishedWhenEveryRegionIsFinal() {
        val s = skill(
            "par",
            listOf(
                st("par", listOf(enter("par"), row(0, Action.Enter(sp("par/ra"))), row(0, Action.Enter(sp("par/rb")))), listOf(br("par.end", "z"))),
                st("par/ra", listOf(enter("par/ra"), row(0, Action.Emit(ev("par.end")))), final = true),
                st("par/rb", branches = listOf(br("timer(5)", "par/rb2", min = 5, max = 5))),
                st("par/rb2", listOf(enter("par/rb2"), row(0, Action.Emit(ev("par.end")))), final = true),
                st("z", final = true),
            ),
        )
        val fx = Fx(s)
        fx.run(8)
        assertThat(fx.trace.log).contains("enter:z@5")
        assertThat(fx.trace.log.filter { it.startsWith("enter:z") }).hasSize(1)
    }

    // ---- trace (item 17) --------------------------------------------------------------------------------------------

    // ---- item 14.3: Lock.until --------------------------------------------------------------------------------------

    // ---- live values: num, params, sound category -------------------------------------------------------------------

    // ---- rig: held item and frame points (v2 RigPerformer, 93cd534e4~1) -------------------------------------------

    // ---- set rows + particle_def (item 15c) -------------------------------------------------------------------------

    // ---- hit: sweep steps, sector, every, oriented box --------------------------------------------------------------

    private fun sector(height: Double) = HitShape.Sector(
        Val.Known(Vector3d(0.0, 0.0, 0.0)), Val.Known(Vector3d(0.0, 0.0, 1.0)), Val.Known(90.0), Val.Known(6.0), Val.Known(height), false,
    )

    // ---- projectile body flight (constant speed / ramp) and fade ----------------------------------------------------

    @Test
    fun fadeSetsTextOpacityOnTextAndFaultsOnItemDisplays() {
        fun play(kind: SpawnKind, look: Map<String, Val<Any>>): Fx {
            val d = id("d")
            val pose = Pose(Val.Known(Vector3d()), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0)))
            val hooks = object : PlayerHooks {
                override fun setDisplayField(handle: VisualHandle, field: String, value: Double) = true
            }
            val fx = Fx(
                skill("a", listOf(st("a", listOf(enter("a"), row(0, Action.Spawn(kind, null, "", pose, Look(look)), d), row(1, Action.Set("text_opacity", Val.Known(100.0)), d))))),
                hooks = hooks,
            )
            fx.run(2)
            return fx
        }
        assertThat(play(SpawnKind.TEXT, mapOf<String, Val<Any>>("text" to Val.Known("hi"))).player.faults()).isEmpty()
        assertThat(play(SpawnKind.BLOCK, mapOf<String, Val<Any>>("block" to Val.Known("stone"))).player.faults()).isNotEmpty()
    }

}
