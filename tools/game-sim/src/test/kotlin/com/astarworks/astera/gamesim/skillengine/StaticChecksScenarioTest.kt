package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.StaticChecks
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Bounds
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Pose
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 rev4.14 static checks: 8 (cast-owned handler gameplay rows), 8b (certain exit), 10 (line weight). */
class StaticChecksScenarioTest : SkillEngineScenario() {
    private fun skill(handlerRow: String, stateFor: String = "", tail: String = ""): String = """
        apiVersion: astera/v3
        kind: Skill
        id: static-probe
        item: couture-rapier
        meta: {cooldown_ticks: 60, resource_cost: 0}
        anchors:
          - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: follow}
        machine:
          start: cast$tail
          states:
            cast:
              $stateFor
              on:
                - {event: bolt.end, to: done}
              do:
                - id: bolt
                  op: projectile
                  origin: muzzle
                  velocity: "= look(caster) * 1.0"
                  speed: 1.0
                  for: 18
                  radius: 0.4
                  hit: {targets: enemies, once: true, attach: false}
                  block: {mode: despawn}
                  on:
                    hit:
                      - $handlerRow
            done: {final: true}
    """.trimIndent()

    private fun failure(yaml: String): SkillDecodeError = when (val r = loadInline(yaml)) {
        is Result.Success -> throw AssertionError("expected a load error but it compiled")
        is Result.Failure -> r.error
    }

    private val lateParticle = "{id: sp, op: particle, particle: electric_spark, anchor: contact, count: 3, at: 3%s}"

    @Test
    fun certainExitRefusesLateHandlerVisual() {
        val err = failure(skill(lateParticle.format("")))
        assertThat(err).isInstanceOf(SkillDecodeError.HandlerCertainExit::class.java)
        assertThat(err.message).contains("plays 3 ticks after cast/bolt.hit").contains("leaves on cast/bolt.end").contains("detach: true")
    }

    @Test
    fun detachedLateHandlerVisualAccepted() {
        val r = loadInline(skill(lateParticle.format(", detach: true"), tail = "\n          tail: 4"))
        assertThat(r).describedAs("detach: true keeps the row for the tail").isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun handlerGameplayRowPastStateEndIsBoundedByCastLength() {
        val s = (loadInline(skill("{id: dmg, op: damage, target: event.victim, damage: {electric: 5}, at: 3}", stateFor = "for: 20\n              then: done")) as? Result.Success)?.value
            ?: throw AssertionError("a cast-owned gameplay row past the state's end must load")
        val hit = s.table.subTables.entries.single { it.key.value.endsWith(".hit") }.value
        assertThat(s.table.bounds.path.max).describedAs("cast length = event window max + last gameplay row").isEqualTo(hit.window.max + 3)
        assertThat(s.table.bounds.path.max).isGreaterThan(20)
    }

    @Test
    fun lineSegmentsWeighTheDisplayCap() {
        fun line(i: Int, segments: Int?): Row {
            val look = linkedMapOf<String, Val<Any>>("shape" to Val.Known("line"))
            segments?.let { look["segments"] = Val.Known(it) }
            val pose = Pose(Val.Known(Vector3d()), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0)))
            return Row(0, InstanceId(listOf(InstanceId.Segment(OpId("l$i"), null))), Action.Spawn(SpawnKind.RIBBON, null, "", pose, Look(look)), SrcPath("t"))
        }
        fun table(rows: List<Row>) = ExpansionTable(
            skillId = "t", sampleSeed = 1L, params = emptyMap(),
            bounds = Bounds(emptyMap(), 0, 0, 0, 0, TickRange(0, 10), 4),
            states = listOf(StateTable(StatePath("a"), Duration.Fixed(10), null, true, rows, emptyList())),
            subTables = emptyMap(), writes = emptyMap(), conditions = emptyMap(), config = EngineConfig(),
        )
        val cfg = EngineConfig()
        // 3 rows x segments 24 x 2 quads = 144 > 64
        val err = (StaticChecks.run(table((1..3).map { line(it, 24) }), cfg) as Result.Failure).error
        assertThat(err).isInstanceOf(SkillDecodeError.LiveDisplayCap::class.java)
        assertThat(err.message).contains("144 displays")
        // a line without segments is one segment = 2 displays; 32 of them fit the cap of 64, 33 do not
        assertThat(StaticChecks.run(table((1..32).map { line(it, null) }), cfg)).isInstanceOf(Result.Success::class.java)
        assertThat(StaticChecks.run(table((1..33).map { line(it, null) }), cfg)).isInstanceOf(Result.Failure::class.java)
    }
}
