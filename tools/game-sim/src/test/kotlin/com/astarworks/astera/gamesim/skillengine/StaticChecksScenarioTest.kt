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
        // 3 rows x segments 24 x 1 item display = 72 > 64
        val err = (StaticChecks.run(table((1..3).map { line(it, 24) }), cfg) as Result.Failure).error
        assertThat(err).isInstanceOf(SkillDecodeError.LiveDisplayCap::class.java)
        assertThat(err.message).contains("72 displays")
        // a line without segments is one segment = 1 display; 64 of them fit the cap of 64, 65 do not
        assertThat(StaticChecks.run(table((1..64).map { line(it, null) }), cfg)).isInstanceOf(Result.Success::class.java)
        assertThat(StaticChecks.run(table((1..65).map { line(it, null) }), cfg)).isInstanceOf(Result.Failure::class.java)
    }

    private fun detachedLine(name: String, at: Int, segments: Int, endAt: Int?): List<Row> {
        val id = InstanceId(listOf(InstanceId.Segment(OpId(name), null)))
        val look = linkedMapOf<String, Val<Any>>("shape" to Val.Known("line"), "segments" to Val.Known(segments))
        val pose = Pose(Val.Known(Vector3d()), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0)))
        val spawn = Row(at, id, Action.Spawn(SpawnKind.RIBBON, null, "", pose, Look(look)), SrcPath("t"), detached = true)
        return listOfNotNull(spawn, endAt?.let { Row(it, id, Action.End(id), SrcPath("t"), detached = true) })
    }

    private fun detachedTable(rows: List<Row>) = ExpansionTable(
        skillId = "t", sampleSeed = 1L, params = emptyMap(),
        bounds = Bounds(emptyMap(), 0, 0, 0, 0, TickRange(0, 10), 4, tail = 6),
        states = listOf(StateTable(StatePath("a"), Duration.Fixed(10), null, true, rows.sortedBy { it.tick }, emptyList())),
        subTables = emptyMap(), writes = emptyMap(), conditions = emptyMap(), config = EngineConfig(),
    )

    @Test
    fun detachedLinesThatDoNotOverlapAreCountedByTick() {
        val cfg = EngineConfig()
        // two 40-segment lines = 40 displays each; alive [0,5) and [5,10): the plain sum (80) would refuse, the peak (40) fits
        val apart = detachedLine("a", 0, 40, 5) + detachedLine("b", 5, 40, 10)
        assertThat(StaticChecks.run(detachedTable(apart), cfg)).isInstanceOf(Result.Success::class.java)
        // moved one tick earlier they coexist for a tick: 80 > 64
        val overlapping = detachedLine("a", 0, 40, 5) + detachedLine("b", 4, 40, 9)
        val err = (StaticChecks.run(detachedTable(overlapping), cfg) as Result.Failure).error
        assertThat(err).isInstanceOf(SkillDecodeError.LiveDisplayCap::class.java)
        assertThat(err.message).contains("80 displays")
    }

    @Test
    fun detachedLineWithoutFixedEndCountsToCastEndPlusTail() {
        val cfg = EngineConfig()
        // "a" has no End row: its interval is not fixed, so it lives to cast end + tail and meets "b" even though b starts long after a would have ended
        val open = detachedLine("a", 0, 40, null) + detachedLine("b", 6, 40, 9)
        val err = (StaticChecks.run(detachedTable(open), cfg) as Result.Failure).error
        assertThat(err).isInstanceOf(SkillDecodeError.LiveDisplayCap::class.java)
        assertThat(err.message).contains("80 displays")
        // the open line alone, or followed by nothing alive with it, is 40
        assertThat(StaticChecks.run(detachedTable(detachedLine("a", 0, 40, null) + detachedLine("b", 0, 2, 3)), cfg)).isInstanceOf(Result.Success::class.java)
    }

    /** chain-lightning's shape (first link + 3 hops every 3 ticks, each a core + glow line alive 5 ticks) at 8 segments per line. */
    private fun chainProbe(linkSegments: Int, arcSegments: Int, withCore: Boolean): String {
        val core = if (!withCore) "" else """
                    - id: core
                      op: display
                      kind: sprite
                      sprite: thunder-spark
                      anchor: bolt
                      size: [0.7, 0.7]
                      color: "#b8e0ff"
                      billboard: center
                      at: 0
                      until: bolt.end"""
        return """
            apiVersion: astera/v3
            kind: Skill
            id: chain-probe
            item: chain-rod
            meta: {cooldown_ticks: 90, resource_cost: 0}
            anchors:
              - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: snapshot}
              - {id: fx,     anchor: caster.eye, offset: {forward: 1.0, up: -0.2}, mode: follow}
              - {id: heart,  anchor: victim.root, offset: {up: 1.2}, mode: follow}
            fragments:
              arc:
                frames_in: {spot: anchor}
                do:
                  - {id: arc_glow, op: ribbon, from: from.center, to: victim.center, thickness: 0.22, color: "#6690d0ff", segments: $arcSegments, jitter: {spread: 0.3, along: 0.2, every: 2}, at: 0, for: 5, detach: true}
                  - {id: arc,      op: ribbon, from: from.center, to: victim.center, thickness: 0.07, color: "#ffeaf6ff", segments: $arcSegments, jitter: {spread: 0.3, along: 0.2, every: 2}, at: 0, for: 5, detach: true}
            machine:
              start: cast
              tail: 14
              states:
                cast:
                  on:
                    - {event: bolt.end, to: done}
                  do:
                    - id: bolt
                      op: projectile
                      origin: muzzle
                      velocity: "= look(caster) * 2.6"
                      speed: 2.6
                      for: 40
                      radius: 0.5
                      hit: {targets: enemies, once: true, attach: false}
                      block: {mode: despawn}
                      on:
                        hit:
                          - {id: link_glow, op: ribbon, from: fx, to: heart, thickness: 0.22, color: "#6690d0ff", segments: $linkSegments, jitter: {spread: 0.3, along: 0.2, every: 2}, at: 0, for: 5, detach: true}
                          - {id: link, op: ribbon, from: fx, to: heart, thickness: 0.07, color: "#ffeaf6ff", segments: $linkSegments, jitter: {spread: 0.3, along: 0.2, every: 2}, at: 0, for: 5, detach: true}
                          - {id: jump, op: use, use: "fragment:arc", chain: {hops: 3, every: 3, pick: {kind: enemies, around: from, radius: 4.5, nearest: 1, exclude: chain}}, frames: {spot: heart}}
$core
                done: {final: true}
        """.trimIndent()
    }

    @Test
    fun chainLightningLinksAtEightSegmentsFit() {
        // first link + 3 hops every 3 ticks, each a core + glow line of 8 segments alive 5 ticks: at most two links coexist = 2 links x 2 lines x 8 = 32
        val r = loadInline(chainProbe(8, 8, withCore = false))
        assertThat(r).describedAs((r as? Result.Failure)?.error?.message ?: "").isInstanceOf(Result.Success::class.java)
        // the bolt's own sprite is one more display alive while the links are: 33, still under the cap
        assertThat(loadInline(chainProbe(8, 8, withCore = true))).isInstanceOf(Result.Success::class.java)
        // 24-segment links: 2 x 2 x 24 + 1 = 97 > 64
        val err = failure(chainProbe(24, 24, withCore = true))
        assertThat(err).isInstanceOf(SkillDecodeError.LiveDisplayCap::class.java)
        assertThat(err.message).contains("97 displays")
    }
}
