package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.CastTrace
import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.LiveValueSource
import com.astarworks.astera.core.application.skillengine.runtime.player.RowScope
import com.astarworks.astera.core.application.skillengine.runtime.player.PlayerHooks
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Branch
import com.astarworks.astera.core.domain.skillengine.table.LockMove
import com.astarworks.astera.core.domain.skillengine.table.Bounds
import com.astarworks.astera.core.domain.skillengine.table.Callee
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import com.astarworks.astera.core.domain.skillengine.table.HitShape
import com.astarworks.astera.core.domain.skillengine.table.HitWindow
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.LockEnd
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Pose
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.HitTargets
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import org.junit.jupiter.api.Test
import java.util.UUID

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

    private fun exitSub(state: String, reason: ExitReason) =
        sub("$state.exit[${reason.name.lowercase()}]", state, row(0, Action.Exit(sp(state), reason)))

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

    @Test
    fun transitionPastesDescendantExitsInnermostFirstThenOwnExitThenOn() {
        val s = skill(
            "p",
            listOf(
                st("p", listOf(enter("p"), row(0, Action.Enter(sp("p/q")))), listOf(br("stop", "z", "p.on[stop]"))),
                st("p/q", listOf(enter("p/q"), row(0, Action.Enter(sp("p/q/c"))))),
                st("p/q/c"),
                st("z", final = true),
            ),
            listOf(
                exitSub("p/q/c", ExitReason.PREEMPT), exitSub("p/q", ExitReason.PREEMPT), exitSub("p", ExitReason.EVENT),
                sub("p.on[stop]", "p", row(0, sound("on"))),
            ),
        )
        val fx = Fx(s)
        fx.run(5, mapOf(3 to listOf("stop")))
        assertThat(fx.trace.log.filter { it.startsWith("exit:") }).containsExactly(
            "exit:p/q/c:PREEMPT@3", "exit:p/q:PREEMPT@3", "exit:p:EVENT@3",
        )
        assertThat(fx.trace.log).contains("enter:z@3")
        assertThat(fx.sounds().map { it.tick }).containsExactly(3)
        assertThat(fx.player.faults()).isEmpty()
    }

    @Test
    fun handledEventIsConsumedByTheInnermostHandler() {
        val s = skill(
            "p",
            listOf(
                st("p", listOf(enter("p"), row(0, Action.Enter(sp("p/c")))), listOf(br("h", "z"))),
                st("p/c", branches = listOf(br("h", null, "p/c.on[h]"))),
                st("z", final = true),
            ),
            listOf(sub("p/c.on[h]", "p/c", row(0, sound("inner")))),
        )
        val fx = Fx(s)
        fx.run(4, mapOf(2 to listOf("h")))
        assertThat(fx.trace.log).doesNotContain("enter:z@2")
        assertThat(fx.sounds()).hasSize(1)
    }

    @Test
    fun aLevelTransitionsOncePerTickAndTheSecondEventBubbles() {
        val s = skill(
            "p",
            listOf(
                st("p", listOf(enter("p"), row(0, Action.Enter(sp("p/c")))), listOf(br("b", "z"))),
                st("p/c", branches = listOf(br("a", "p/c2"))),
                st("p/c2", branches = listOf(br("b", "p/c3"))),
                st("p/c3"),
                st("z", final = true),
            ),
        )
        val fx = Fx(s)
        fx.run(4, mapOf(2 to listOf("a", "b")))
        assertThat(fx.trace.log).contains("enter:p/c2@2", "enter:z@2")
        assertThat(fx.trace.log).doesNotContain("enter:p/c3@2")
    }

    // ---- cancel (item 7): the player's job --------------------------------------------------------------------------

    @Test
    fun cancelCastExitsLevelsInnermostFirstWithCancelReasonThenEnds() {
        val s = skill(
            "p",
            listOf(st("p", listOf(enter("p"), row(0, Action.Enter(sp("p/c"))))), st("p/c")),
            listOf(exitSub("p/c", ExitReason.CANCEL), exitSub("p", ExitReason.CANCEL)),
        )
        val fx = Fx(s)
        fx.run(2)
        fx.player.cancel(fx.cast, CancelScope.Cast)
        assertThat(fx.trace.log.filter { it.startsWith("exit:") }).containsExactly("exit:p/c:CANCEL@2", "exit:p:CANCEL@2")
        fx.run(3)
        assertThat(fx.player.live()).isEmpty()
    }

    @Test
    fun cancelStateGoesToThenWithCancelReason() {
        val s = skill(
            "a",
            listOf(
                st("a", listOf(enter("a"), row(2, Action.Cancel(CancelScope.State))), then = "b"),
                st("b", final = true),
            ),
            listOf(exitSub("a", ExitReason.CANCEL)),
        )
        val fx = Fx(s)
        fx.run(4)
        assertThat(fx.trace.log).contains("exit:a:CANCEL@2", "enter:b@2")
        assertThat(fx.player.faults()).isEmpty()
    }

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

    @Test
    fun traceSeesEnterExitEventAndCallRows() {
        val s = skill(
            "a",
            listOf(
                st(
                    "a",
                    listOf(
                        enter("a"),
                        row(1, Action.Call(Callee.PartRef("x"), id("call"), emptyMap(), emptyMap())),
                        row(1, Action.Emit(ev("ping"))),
                    ),
                    listOf(br("go", "z")),
                ),
                st("z", final = true),
            ),
            listOf(exitSub("a", ExitReason.EVENT)),
        )
        val fx = Fx(s)
        fx.run(4, mapOf(3 to listOf("go")))
        assertThat(fx.trace.log).containsSubsequence("enter:a@0", "call:call@1", "event:ping@1", "exit:a:EVENT@3", "enter:z@3")
    }

    // ---- item 14.3: Lock.until --------------------------------------------------------------------------------------

    private class LockHooks : PlayerHooks {
        val log = ArrayList<String>()
        var now = 0
        override fun lock(caster: UUID, move: LockMove, pitch: Boolean, yaw: Boolean, untilTick: Int): Boolean {
            log += "lock:$untilTick@$now"
            return true
        }

        override fun unlock(caster: UUID, move: LockMove, pitch: Boolean, yaw: Boolean) {
            log += "unlock@$now"
        }
    }

    @Test
    fun lockAtTickIsRelativeToTheStateBase() {
        val s = skill(
            "a",
            listOf(
                st("a", branches = listOf(br("go", "b"))),
                st("b", listOf(enter("b"), row(0, Action.Lock(move = LockMove.ALL, pitch = false, yaw = false, until = LockEnd.AtTick(10))))),
            ),
        )
        val hooks = LockHooks()
        val fx = Fx(s, hooks = hooks)
        // LockHooks.now is the test's clock: drive it per tick. The lock is entered at tick 4 (state base) with AtTick(10) = base + 10 = 14 (Action.kt: "relative to the state base")
        for (t in 0..5) {
            hooks.now = t
            fx.run(t, if (t == 4) mapOf(4 to listOf("go")) else emptyMap())
        }
        assertThat(hooks.log).contains("lock:14@4")
    }

    @Test
    fun lockUntilCastEndIsReleasedWhenTheOwningLevelExits() {
        val s = skill(
            "a",
            listOf(
                st("a", listOf(enter("a"), row(0, Action.Lock(move = LockMove.ALL, pitch = true, yaw = true, until = LockEnd.CastEnd))), listOf(br("go", "z"))),
                st("z", final = true),
            ),
        )
        val hooks = LockHooks()
        val fx = Fx(s, hooks = hooks)
        for (t in 0..5) {
            hooks.now = t
            fx.run(t, if (t == 3) mapOf(3 to listOf("go")) else emptyMap())
        }
        assertThat(hooks.log.first()).startsWith("lock:")
        assertThat(hooks.log).contains("unlock@3")
        assertThat(fx.player.faults()).isEmpty()
    }

    // ---- live values: num, params, sound category -------------------------------------------------------------------

    @Test
    fun liveNumComesFromTheValueSourceAndParamsFromTheTable() {
        val rec = GatewayRecorder()
        val source = object : LiveValueSource by rec.live {
            override fun num(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Double = if (name == "speed") 7.5 else 0.0
        }
        val s = skill(
            "a",
            listOf(st("a", listOf(enter("a"), row(0, sound("live", Val.Live("speed", null))), row(0, sound("param", Val.Live("\${p}", null)))))),
            params = mapOf("p" to 3.0),
        )
        val fx = Fx(s, rec, source)
        fx.run(1)
        val vols = fx.sounds().map { it.payload }
        assertThat(vols[0]).contains("vol=" + RecordedCall.num(7.5))
        assertThat(vols[1]).contains("vol=" + RecordedCall.num(3.0))
        assertThat(fx.player.faults()).isEmpty()
    }

    /**
     * R10: a live cell is read with the row's own seed and copy indices, so Paper evaluates `rand()` / `i` / `parent.i` as the compiler folded them
     * (the cast seed alone would give every `times` copy the same stream).
     */
    @Test
    fun liveCellsAreReadWithTheRowsSeedAndCopyIndices() {
        val rec = GatewayRecorder()
        val seen = ArrayList<RowScope>()
        val source = object : LiveValueSource by rec.live {
            override fun num(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Double {
                seen += scope
                return 1.0
            }
        }
        val a = InstanceId(listOf(InstanceId.Segment(OpId("grp"), 2), InstanceId.Segment(OpId("snd"), 0)))
        val b = InstanceId(listOf(InstanceId.Segment(OpId("grp"), 2), InstanceId.Segment(OpId("snd"), 1)))
        val s = skill(
            "a",
            listOf(st("a", listOf(enter("a"), row(0, sound("x", Val.Live("probe", null)), a), row(0, sound("y", Val.Live("probe", null)), b)))),
        )
        val fx = Fx(s, rec, source)
        fx.run(1)
        assertThat(fx.player.faults()).isEmpty()
        assertThat(seen.map { it.indices }).containsExactly(listOf(0, 2), listOf(1, 2))
        assertThat(seen[0].seed).isEqualTo(com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings.seed(1L, OpId("snd"), 0))
        assertThat(seen[1].seed).isEqualTo(com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings.seed(1L, OpId("snd"), 1))
        assertThat(seen[0].seed).isNotEqualTo(seen[1].seed)
        assertThat(seen.map { it.castSeed }.toSet()).containsExactly(1L)
    }

    // ---- rig: held item and frame points (v2 RigPerformer, 93cd534e4~1) -------------------------------------------

    private fun rigPayload(parent: InstanceId, of: InstanceId?, extra: Map<String, Val<Any>> = emptyMap()): Map<String, Val<Any>> {
        val zero = Vector3d()
        return buildMap {
            put("parent", Val.Known(parent))
            of?.let { put("of", Val.Known(it)) }
            put("hand", Val.Known(false))
            put("pivot", Val.Known(Vector3d(0.0, 1.0, 0.0)))
            put("tilt", Val.Known(Vector3d(zero)))
            put("rest", Val.Known(Vector3d(zero)))
            put("swing", Val.Known(emptyList<Any>()))
            put("sample_ticks", Val.Known(1))
            put("duration", Val.Known(10))
            put("respect_hit_stop", Val.Known(false))
            put("body_respect_hit_stop", Val.Known(false))
            put("freezes", Val.Known(emptyList<Any>()))
            putAll(extra)
        }
    }

    private val unitPose = Pose(Val.Known(Vector3d()), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0)))

    @Test
    fun aRigWithoutOfCarriesTheHeldItemAndRemovesItAtTheEnd() {
        // v2: the rig is its own frame and the caster's main-hand item rides it as the part (RigPerformer.start / withPartFrame)
        val r = id("r")
        val s = skill(
            "a",
            listOf(
                st(
                    "a",
                    listOf(
                        enter("a"),
                        row(0, Action.Effect(OpId("rig"), rigPayload(id("caster"), null)), r),
                        row(10, Action.End(r), r),
                    ),
                ),
            ),
        )
        val rec = GatewayRecorder()
        val player = playProbe(s, ticks = 12, rec = rec)
        assertThat(player.faults()).isEmpty()
        assertThat(rec.calls.filter { it.verb == "spawn" }.map { it.payload }).containsExactly("kind=item")
        assertThat(rec.calls.filter { it.verb == "write" }).isNotEmpty()
        assertThat(rec.calls.single { it.verb == "remove" }.tick).isEqualTo(10)
    }

    @Test
    fun rigTipResolvesOnTheDrivenFrame() {
        // v2 FramePoint(frame, "tip", local): the point rides the frame (origin + rotation * local)
        val slam = id("slam")
        val txt = id("txt")
        val s = skill(
            "a",
            listOf(
                st(
                    "a",
                    listOf(
                        enter("a"),
                        row(0, Action.Spawn(SpawnKind.FRAME, null, "caster", unitPose, Look(emptyMap())), slam),
                        row(0, Action.Spawn(SpawnKind.TEXT, null, "", unitPose, Look(mapOf<String, Val<Any>>("text" to Val.Known("hi")))), txt),
                        row(0, Action.Effect(OpId("rig"), rigPayload(id("caster"), slam, mapOf("tip" to Val.Known(Vector3d(0.0, 0.0, 2.0))))), id("r")),
                        row(5, Action.Key(txt, 0, Val.Live("slam.tip", null), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0))), txt),
                    ),
                ),
            ),
        )
        val rec = GatewayRecorder()
        val writes = ArrayList<Vec3>()
        val visual = object : com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway by rec.visual {
            override fun write(handle: VisualHandle, pose: com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose, interpolationTicks: Int) {
                writes += pose.frame.origin
                rec.visual.write(handle, pose, interpolationTicks)
            }
        }
        val player = playProbe(s, ticks = 6, rec = rec, visual = visual)
        assertThat(player.faults()).isEmpty()
        // caster stand-in (0, 1, 0) + rig pivot (0, 1, 0) + tip (0, 0, 2)
        assertThat(writes).hasSize(1)
        assertThat(writes[0].x).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(writes[0].y).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(writes[0].z).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9))
    }

    @Test
    fun soundCategoryIsPassedThrough() {
        val s = skill("a", listOf(st("a", listOf(enter("a"), row(0, sound("s", category = "voice"))))))
        val fx = Fx(s)
        fx.run(1)
        assertThat(fx.sounds().single().payload).contains("cat=voice")
    }

    // ---- set rows + particle_def (item 15c) -------------------------------------------------------------------------

    @Test
    fun setCountAndVolumeAreFoldedIntoLaterRowsOfTheInstance() {
        val target = id("fx")
        val s = skill(
            "a",
            listOf(
                st(
                    "a",
                    listOf(
                        enter("a"),
                        row(0, Action.Set("count", Val.Known(7)), target),
                        row(0, Action.ParticleDef("my_def", Val.Known(Vector3d())), target),
                        row(0, Action.Set("volume", Val.Known(0.25)), target),
                        row(0, sound("s"), target),
                    ),
                ),
            ),
        )
        val fx = Fx(s)
        fx.run(1)
        assertThat(fx.rec.calls.single { it.verb == "particle" }.payload).isEqualTo("type=my_def count=7")
        assertThat(fx.sounds().single().payload).contains("vol=" + RecordedCall.num(0.25))
        assertThat(fx.player.faults()).isEmpty()
    }

    @Test
    fun setTextOpacityReachesTheDisplayHook() {
        val text = id("text")
        val s = skill(
            "a",
            listOf(
                st(
                    "a",
                    listOf(
                        enter("a"),
                        row(0, Action.Spawn(SpawnKind.TEXT, null, "", Pose(Val.Known(Vector3d()), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0))), Look(mapOf<String, Val<Any>>("text" to Val.Known("hi")))), text),
                        row(1, Action.Set("text_opacity", Val.Known(0.5)), text),
                    ),
                ),
            ),
        )
        val seen = ArrayList<Pair<String, Double>>()
        val hooks = object : PlayerHooks {
            override fun setDisplayField(handle: VisualHandle, field: String, value: Double): Boolean {
                seen += field to value
                return true
            }
        }
        val fx = Fx(s, hooks = hooks)
        fx.run(2)
        assertThat(seen).containsExactly("text_opacity" to 0.5)
        assertThat(fx.player.faults()).isEmpty()
    }

    // ---- hit: sweep steps, sector, every, oriented box --------------------------------------------------------------

    private fun victim(feet: Vec3) = HitCandidate(
        UUID.randomUUID(), feet, 0.0, HitBox(Vec3(feet.x - 0.3, feet.y, feet.z - 0.3), Vec3(feet.x + 0.3, feet.y + 1.8, feet.z + 0.3)),
    )

    private val everyone = HitTargets("enemies", null)

    private fun hitRow(tick: Int, shape: HitShape, window: HitWindow = HitWindow.Once, anchor: InstanceId? = null, rule: HitTargets = everyone) =
        row(tick, Action.Hit(shape, anchor, "", rule, window, false, true, emptyList()), id("hit1"))

    private fun hitState(vararg rows: Row, extra: List<Row> = emptyList()) =
        st("a", listOf(enter("a")) + extra + rows.toList())

    private val onHit = sub("hit1.hit", "a", row(0, sound("hit")))

    private fun sector(height: Double) = HitShape.Sector(
        Val.Known(Vector3d(0.0, 0.0, 0.0)), Val.Known(Vector3d(0.0, 0.0, 1.0)), Val.Known(90.0), Val.Known(6.0), Val.Known(height), false,
    )

    @Test
    fun sectorHitChecksTheVerticalHalfHeight() {
        val rec = GatewayRecorder()
        rec.victims = listOf(victim(Vec3(0.0, 0.0, 3.0)), victim(Vec3(0.0, 5.0, 3.0)))
        val fx = Fx(skill("a", listOf(hitState(hitRow(0, sector(2.0)))), listOf(onHit)), rec)
        fx.run(2)
        assertThat(fx.sounds()).hasSize(1)
        assertThat(fx.player.faults()).isEmpty()
    }

    @Test
    fun sweepPosesFollowMinOfCapAndCeilTravelOverRadiusPlusOne() {
        for ((travel, expected) in listOf(10.0 to 11, 100.0 to 32)) {
            val rec = GatewayRecorder()
            var nearbyCalls = 0
            val counting = object : ISkillEntityQuery by rec.query {
                override fun nearby(cast: com.astarworks.astera.core.application.skillengine.runtime.port.CastRef, point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> {
                    nearbyCalls++
                    return rec.query.nearby(cast, point, radius, excluding)
                }
            }
            val frame = id("frame")
            val pose = Pose(Val.Known(Vector3d()), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0)))
            val state = hitState(
                row(0, Action.Spawn(SpawnKind.FRAME, null, "", pose, Look(emptyMap())), frame),
                row(0, Action.Key(frame, 1, Val.Known(Vector3d(0.0, 0.0, travel)), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0))), frame),
                hitRow(1, HitShape.Sphere(Val.Known(Vector3d(0.0, 0.0, travel)), Val.Known(1.0)), anchor = frame),
            )
            val fx = Fx(skill("a", listOf(state)), rec, query = counting)
            fx.run(2)
            assertThat(nearbyCalls).describedAs("travel $travel").isEqualTo(expected)
            assertThat(fx.player.faults()).isEmpty()
        }
    }

    @Test
    fun hitWindowEveryRehitsAfterNTicksOnceNeverDoes() {
        fun hits(window: HitWindow): Int {
            val rec = GatewayRecorder()
            rec.victims = listOf(victim(Vec3(0.0, 0.0, 3.0)))
            val rows = (0..6).map { hitRow(it, sector(2.0), window) }.toTypedArray()
            val fx = Fx(skill("a", listOf(hitState(*rows)), listOf(onHit)), rec)
            fx.run(8)
            return fx.sounds().size
        }
        assertThat(hits(HitWindow.Once)).isEqualTo(1)
        assertThat(hits(HitWindow.Every(3))).isEqualTo(3)
    }

    @Test
    fun orientedBoxHitFollowsTheRotation() {
        fun hits(rotation: Quaterniond): Int {
            val rec = GatewayRecorder()
            rec.victims = listOf(victim(Vec3(0.0, 0.0, 0.8)))
            val shape = HitShape.Box(Val.Known(Vector3d(0.0, 0.5, 0.0)), Val.Known(Vector3d(1.0, 1.0, 0.2)), Val.Known(rotation))
            val fx = Fx(skill("a", listOf(hitState(hitRow(0, shape))), listOf(onHit)), rec)
            fx.run(2)
            return fx.sounds().size
        }
        assertThat(hits(Quaterniond())).isEqualTo(0)
        assertThat(hits(Quaterniond().rotateY(Math.PI / 2))).isEqualTo(1)
    }

    // ---- projectile body flight (constant speed / ramp) and fade ----------------------------------------------------

    private fun bodyHits(look: Map<String, Val<Any>>): Int {
        val body = id("ball")
        val rec = GatewayRecorder()
        rec.victims = listOf(victim(Vec3(0.0, -0.9, 6.0)))
        val pose = Pose(Val.Known(Vector3d()), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0)))
        val state = hitState(
            row(0, Action.Spawn(SpawnKind.PROJECTILE_BODY, null, "", pose, Look(look)), body),
            // the shape point is the body's live position (a lowered cell is world space, ADR-0303 hit: "points are in the anchor frame"); ADR-0303 10.2 tick order: state rows (1) run before projectile motion (2), so a hit row at tick 4 sees the body after 3 flight steps
            hitRow(4, HitShape.Sphere(Val.Live("anchor:ball.position", null), Val.Known(1.0)), anchor = body),
        )
        val fx = Fx(skill("a", listOf(state), listOf(onHit)), rec)
        fx.run(5)
        assertThat(fx.player.faults()).isEmpty()
        return fx.sounds().size
    }

    @Test
    fun projectileBodyFliesAtConstantSpeedAndAlongTheRamp() {
        val v = Val.Known(Vector3d(0.0, 0.0, 1.0)) as Val<Any>
        // constant 2 blocks per tick: z = 6 after 3 steps
        assertThat(bodyHits(mapOf("velocity" to v, "speed" to Val.Known(2.0)))).isEqualTo(1)
        // a ramp 0 -> 4 over 3 ticks (linear) covers 6 blocks in 3 ticks
        assertThat(
            bodyHits(mapOf("velocity" to v, "speed.from" to Val.Known(0.0), "speed.to" to Val.Known(4.0), "speed.for" to Val.Known(3.0), "speed.ease" to Val.Known("linear"))),
        ).isEqualTo(1)
        // a slow body has not arrived
        assertThat(bodyHits(mapOf("velocity" to v, "speed" to Val.Known(0.5)))).isEqualTo(0)
    }

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

    @Test
    fun randGuardDrawsIndependentlyPerFiringAndReplaysIdentically() {
        val compiler = com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler()
        val expr = (compiler.compile("rand() < 0.4", com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec(), com.astarworks.astera.core.domain.skillengine.expr.ExprType.BOOL) as Result.Success).value
        fun play(seed: Long): List<Int> {
            val hooks = object : PlayerHooks {
                override fun guard(cast: CastHandle, guard: com.astarworks.astera.core.domain.skillengine.table.Guard, conditions: Map<String, String>, binding: EventBinding?, localTick: Int, seed: Long): Boolean? =
                    (expr.eval(com.astarworks.astera.core.domain.skillengine.expr.ExprEnv(emptyMap(), object : com.astarworks.astera.core.domain.skillengine.expr.FrameLookup {
                        override fun worldPose(frame: String, point: String?) = null
                        override fun velocity(frame: String, point: String?) = Vec3.ZERO
                    }, seed = seed)) as com.astarworks.astera.core.domain.skillengine.expr.ExprValue.Bool).value
            }
            // one call site, 60 firings (ticks 0..59): each must draw its own 40%
            val rows = listOf(enter("a")) + (0 until 60).map { row(it, sound("x"), id("snd")).copy(guard = com.astarworks.astera.core.domain.skillengine.table.Guard.Expr("rand() < 0.4")) }
            val fx = Fx(skill("a", listOf(st("a", rows))), hooks = hooks)
            fx.run(60)
            return fx.sounds().map { it.tick }
        }
        val first = play(1L)
        assertThat(first.size).isBetween(8, 40)
        assertThat(first).isEqualTo(play(1L))
    }
}
