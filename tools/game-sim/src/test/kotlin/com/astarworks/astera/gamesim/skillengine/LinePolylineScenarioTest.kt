package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A line ribbon with `segments: N` + `jitter` is a polyline (ADR-0303 rev4.14): N+1 vertices, the two ends exactly the live anchors every tick, inner
 * vertices displaced inside `spread` / `along` from the row seed, re-rolled only at `every` ticks; `jitter.follow` makes one line reuse another's stream.
 * Real loader, real TablePlayer, GatewayRecorder.
 */
class LinePolylineScenarioTest : SkillEngineScenario() {
    private fun dummy(z: Double) =
        HitCandidate(UUID(0L, 11L), Vec3(0.0, 0.0, z), 0.0, HitBox(Vec3(-0.3, 0.0, z - 0.3), Vec3(0.3, 1.8, z + 0.3)), 1.62)

    private fun ribbon(id: String, extra: String, color: String = "#ffffff") =
        "{id: $id, op: ribbon, from: caster.eye, to: victim.center, thickness: 0.1, color: \"$color\", $extra at: 0, for: 6, detach: true}"

    private fun skill(vararg handler: String, states: String = "do: []"): String = """
        apiVersion: astera/v3
        kind: Skill
        id: line-polyline
        machine:
          start: cast
          tail: 12
          world:
            - id: bolt
              op: hit
              at: 0
              anchor: caster
              shape: {sphere: 30.0}
              targets: enemies
              max_count: 1
              ignore_invulnerability: false
              damage: {fire: 5}
              on:
                hit:
                  ${handler.joinToString("\n                  ") { "- $it" }}
          states:
            cast:
              for: 30
              $states
    """.trimIndent()

    private fun load(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error.message
    }

    private fun run(yaml: String, seed: Long = 1L): List<RecordedCall> {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = listOf(dummy(4.0))
        return play(load(yaml), seed = seed, events = emptyList(), ticks = 14, rec = rec)
    }

    private fun vec(s: String): Vec3 = s.split(",").let { Vec3(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }

    private fun verts(detail: String): List<Vec3> =
        Regex("""verts=(\S+)""").find(detail)?.groupValues?.get(1)?.split(";")?.map(::vec) ?: emptyList()

    private fun ends(detail: String): Pair<Vec3, Vec3> =
        vec(Regex("""from=(-?[0-9.]+,-?[0-9.]+,-?[0-9.]+)""").find(detail)!!.groupValues[1]) to vec(Regex("""to=(-?[0-9.]+,-?[0-9.]+,-?[0-9.]+)""").find(detail)!!.groupValues[1])

    /** Vertex lists of the ribbon spawned [nth] (0 = first) by tick since its spawn: the last spawn / line call of each tick wins. */
    private fun boltsByTick(calls: List<RecordedCall>, nth: Int = 0): Map<Int, List<Vec3>> {
        val spawns = calls.filter { it.verb == "spawn" && it.payload == "kind=ribbon" }
        val spawn = spawns[nth]
        val name = spawn.instance
        val out = LinkedHashMap<Int, List<Vec3>>()
        for (c in calls) if ((c.verb == "line" || c.verb == "spawn") && c.instance == name && c.tick >= spawn.tick) out[c.tick - spawn.tick] = verts(c.detail)
        return out
    }

    private val jag = "segments: 5, jitter: {spread: 0.4, along: 0.2, every: 2},"

    @Test
    fun verticesFollowAnchorsAndStayInsideSpread() {
        val calls = run(skill(ribbon("beam", jag)))
        val bolts = boltsByTick(calls)
        assertThat(bolts.keys).containsAll(listOf(0, 1, 2, 3, 4, 5))
        val first = bolts.getValue(0)
        for ((tick, v) in bolts) {
            assertThat(v).`as`("tick $tick").hasSize(6)
            // both ends are exactly the anchors every tick
            assertThat(v.first()).isEqualTo(first.first())
            assertThat(v.last()).isEqualTo(first.last())
            val span = v.last() - v.first()
            val dir = span.normalize()
            for (k in 1..4) {
                val off = v[k] - (v.first() + span * (k / 5.0))
                val along = off.dot(dir)
                val perp = off - dir * along
                assertThat(abs(along)).`as`("tick $tick vertex $k along").isLessThanOrEqualTo(0.2 / 5.0 * span.length() + 1e-6)
                assertThat(perp.length()).`as`("tick $tick vertex $k perpendicular").isLessThanOrEqualTo(0.4 * sqrt(2.0) + 1e-6)
            }
        }
        // the spawn records one item-display frame per segment
        val spawn = calls.first { it.verb == "spawn" && it.payload == "kind=ribbon" }
        assertThat(Regex("""\[[^|\]]+\|[^|\]]+\|[^\]]+\]""").findAll(spawn.detail.substringAfter("frames=")).count()).isEqualTo(5)
    }

    @Test
    fun rejagOnlyOnEveryTicks() {
        val bolts = boltsByTick(run(skill(ribbon("beam", jag))))
        assertThat(bolts.getValue(0)).isEqualTo(bolts.getValue(1))
        assertThat(bolts.getValue(2)).isNotEqualTo(bolts.getValue(1))
        assertThat(bolts.getValue(2)).isEqualTo(bolts.getValue(3))
        assertThat(bolts.getValue(4)).isNotEqualTo(bolts.getValue(3))
        // without every: rolled once at spawn, frozen for the whole life
        val frozen = boltsByTick(run(skill(ribbon("beam", "segments: 5, jitter: {spread: 0.4},"))))
        assertThat(frozen.values.toSet()).hasSize(1)
    }

    @Test
    fun sameSeedSameBolt() {
        val a = boltsByTick(run(skill(ribbon("beam", jag)), seed = 1L))
        val b = boltsByTick(run(skill(ribbon("beam", jag)), seed = 1L))
        val c = boltsByTick(run(skill(ribbon("beam", jag)), seed = 2L))
        assertThat(b).isEqualTo(a)
        assertThat(c.getValue(0)).isNotEqualTo(a.getValue(0))
    }

    @Test
    fun straightThreadUnchanged() {
        val calls = run(skill(ribbon("beam", "")))
        val spawn = calls.first { it.verb == "spawn" && it.payload == "kind=ribbon" }
        assertThat(spawn.detail).doesNotContain("verts=")
        assertThat(Regex("""\[[^|\]]+\|[^|\]]+\|[^\]]+\]""").findAll(spawn.detail.substringAfter("frames=")).count()).isEqualTo(1)
        val lines = calls.filter { it.verb == "line" }
        assertThat(lines).isNotEmpty
        assertThat(lines.all { !it.detail.contains("verts=") }).isTrue()
        // a one-segment line writes the same ends as a two-vertex polyline would
        val (f, t) = ends(spawn.detail)
        assertThat(f.y).isEqualTo(1.62)
        assertThat(t.z).isEqualTo(4.0)
    }

    @Test
    fun followReusesTheTargetStream() {
        // the glow has half the spread of the core and no slide: every inner offset is exactly half of the core's
        val glow = ribbon("glow", "segments: 5, jitter: {spread: 0.2, every: 2, follow: core},", "#88ccff")
        val coreNoAlong = ribbon("core", "segments: 5, jitter: {spread: 0.4, every: 2},", "#ffffff")
        for (handler in listOf(listOf(coreNoAlong, glow), listOf(glow, coreNoAlong))) {
            val calls = run(skill(*handler.toTypedArray()))
            val spawns = calls.filter { it.verb == "spawn" && it.payload == "kind=ribbon" }
            val coreIdx = spawns.indexOfFirst { it.detail.contains("#ffffff") }
            val glowIdx = spawns.indexOfFirst { it.detail.contains("#88ccff") }
            val c = boltsByTick(calls, coreIdx)
            val g = boltsByTick(calls, glowIdx)
            for (tick in c.keys) {
                val cv = c.getValue(tick)
                val gv = g.getValue(tick)
                val span = cv.last() - cv.first()
                for (k in 1..4) {
                    val base = cv.first() + span * (k / 5.0)
                    val co = cv[k] - base
                    val go = gv[k] - base
                    assertThat((go - co * 0.5).length()).`as`("tick $tick vertex $k").isLessThan(1e-6)
                }
            }
        }
        // without follow the two rows are different bolts
        val apart = run(skill(coreNoAlong, ribbon("glow", "segments: 5, jitter: {spread: 0.2, every: 2},", "#88ccff")))
        val spawns = apart.filter { it.verb == "spawn" && it.payload == "kind=ribbon" }
        val c = boltsByTick(apart, spawns.indexOfFirst { it.detail.contains("#ffffff") }).getValue(0)
        val g = boltsByTick(apart, spawns.indexOfFirst { it.detail.contains("#88ccff") }).getValue(0)
        val span = c.last() - c.first()
        assertThat((g[2] - (c.first() + span * 0.4)) - (c[2] - (c.first() + span * 0.4)) * 0.5).isNotEqualTo(Vec3.ZERO)
    }

    @Test
    fun followLoadErrors() {
        val core = ribbon("core", "segments: 5, jitter: {spread: 0.4, every: 2},")
        // different segments
        assertThat(failure(skill(core, ribbon("glow", "segments: 6, jitter: {spread: 0.2, follow: core},"))))
            .contains("jitter.follow 'core'").contains("segments: 5").contains("segments: 6")
        // the target is a straight thread (segments 1)
        assertThat(failure(skill(ribbon("core", ""), ribbon("glow", "segments: 6, jitter: {spread: 0.2, follow: core},")))).contains("segments: 1")
        // unknown target
        assertThat(failure(skill(core, ribbon("glow", "segments: 5, jitter: {spread: 0.2, follow: nowhere},")))).contains("jitter.follow 'nowhere' is not a line ribbon")
        // a target in another body (a state, not this handler)
        val inState = "do:\n                - " + ribbon("core", "segments: 5, jitter: {spread: 0.4},")
        assertThat(failure(skill(ribbon("glow", "segments: 5, jitter: {spread: 0.2, follow: core},"), states = inState))).contains("jitter.follow 'core' is not a line ribbon")
        // a target that is not a line
        assertThat(failure(skill(core, "{id: snd, op: sound, sound: \"astera:x\", anchor: caster}", ribbon("glow", "segments: 5, jitter: {spread: 0.2, follow: snd},")))).contains("jitter.follow 'snd'")
        // itself
        assertThat(failure(skill(ribbon("glow", "segments: 5, jitter: {spread: 0.2, follow: glow},")))).contains("cannot name the ribbon itself")
    }

    @Test
    fun loadErrors() {
        fun err(extra: String) = failure(skill(ribbon("beam", extra)))
        assertThat(err("segments: 40, jitter: {spread: 0.3},")).contains("ribbon 'beam': segments must be a whole number 1..24 (got 40")
        assertThat(err("segments: 2.5, jitter: {spread: 0.3},")).contains("segments must be a whole number 1..24")
        assertThat(err("segments: 0, jitter: {spread: 0.3},")).contains("segments must be a whole number 1..24")
        assertThat(err("jitter: {spread: 0.3},")).contains("jitter: needs segments >= 2")
        assertThat(err("segments: 1, jitter: {spread: 0.3},")).contains("jitter: needs segments >= 2")
        assertThat(err("segments: 8,")).contains("segments: 8 without jitter draws the same straight thread as segments: 1")
        assertThat(err("segments: 4, jitter: {spread: \"= rand()\"},")).contains("jitter.spread must be a literal > 0 (got \"= rand()\")")
        assertThat(err("segments: 4, jitter: {spread: 0},")).contains("jitter.spread must be a literal > 0")
        assertThat(err("segments: 4, jitter: {along: 0.1},")).contains("jitter.spread must be a literal > 0")
        assertThat(err("segments: 4, jitter: {spread: 0.3, along: 0.5},")).contains("jitter.along must be a literal in [0, 0.5)")
        assertThat(err("segments: 4, jitter: {spread: 0.3, every: 0},")).contains("jitter.every must be a literal whole number >= 1")
        assertThat(err("segments: 4, jitter: {spread: 0.3, wobble: 1},")).contains("unknown key 'wobble' on jitter")
        // a trail refuses both
        val trail = failure(skill("{id: t, op: ribbon, of: caster.eye, style: soft, width: 0.2, history: 5, color: \"#ffffff\", segments: 4, jitter: {spread: 0.3}, at: 0, for: 6}"))
        assertThat(trail).contains("line fields")
    }
}
