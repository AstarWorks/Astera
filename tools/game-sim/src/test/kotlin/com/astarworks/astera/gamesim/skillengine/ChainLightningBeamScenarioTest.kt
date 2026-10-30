package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The shipped chain-lightning draws every link of the chain as a jagged 8-segment polyline (white core + blue glow that follows it): the first from the
 * wand to the bolt's victim at the hit tick, then one per hop at hit+3/+6/+9 from the previous victim to the new one. Real loader, real player,
 * real `content/skills`, GatewayRecorder.
 */
class ChainLightningBeamScenarioTest : SkillEngineScenario() {
    private fun shipped(id: String): CompiledSkill {
        val c = SkillComposer(contentDir.resolve("skills")).composeAll()
        val composed = (c as? Result.Success)?.value ?: error("compose failed: $c")
        val ids = composed.keys.toSet()
        val defs = composed.mapValues { (_, t) ->
            when (val d = decodeTree(id, t.tree, ids)) {
                is Result.Success -> d.value
                is Result.Failure -> error("decode: ${d.error}")
            }
        }
        return when (val r = compileAgainst(defs.getValue(id), defs)) {
            is Result.Success -> r.value
            is Result.Failure -> error("compile: ${r.error}")
        }
    }

    private fun victim(i: Long, z: Double, x: Double = 0.0) =
        HitCandidate(UUID(0L, 10L + i), Vec3(x, 0.0, z), 0.0, HitBox(Vec3(x - 0.4, 0.0, z - 0.4), Vec3(x + 0.4, 1.8, z + 0.4)), 1.62)

    private fun id(i: Long) = UUID(0L, 10L + i).toString()

    private fun vec(s: String): Vec3 = s.split(",").let { Vec3(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }

    private fun verts(detail: String): List<Vec3> =
        Regex("""verts=(\S+)""").find(detail)?.groupValues?.get(1)?.split(";")?.map(::vec) ?: emptyList()

    private fun run(victims: List<HitCandidate>): List<RecordedCall> {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = victims
        return play(shipped("chain-lightning"), seed = 1L, events = emptyList(), ticks = 80, rec = rec)
    }

    private fun ribbons(calls: List<RecordedCall>) = calls.filter { it.verb == "spawn" && it.payload == "kind=ribbon" && it.detail.contains("verts=") }

    /** Four victims in a row inside 4.5 blocks of each other, the nearest to the caster first. */
    private val four = listOf(victim(1, 8.0), victim(2, 10.0), victim(3, 12.0), victim(4, 14.0))

    @Test
    fun threeHopsAfterTheHit() {
        val calls = run(four)
        val dmg = calls.filter { it.verb == "damage" }
        assertThat(dmg.map { it.instance }).describedAs(dmg.toString()).containsExactly(id(1), id(2), id(3), id(4))
        val hit = dmg[0].tick
        assertThat(dmg.map { it.tick }).containsExactly(hit, hit + 3, hit + 6, hit + 9)
        val amounts = dmg.map { Regex("""electric=([0-9.]+)""").find(it.payload)!!.groupValues[1].toDouble() }
        listOf(18.0, 12.6, 8.82, 6.174).forEachIndexed { i, e -> assertThat(amounts[i]).isCloseTo(e, org.assertj.core.data.Offset.offset(0.01)) }
        val shocks = calls.filter { it.verb == "status" && it.tick > hit }
        assertThat(shocks.map { it.payload }).describedAs(shocks.toString()).allMatch { it.contains("status=shock") }
        assertThat(shocks.map { Regex("""for=(\d+)""").find(it.payload)!!.groupValues[1].toInt() }).containsExactly(6, 4, 2)
    }

    @Test
    fun everyLinkIsAJaggedPolyline() {
        val calls = run(four)
        val hit = calls.first { it.verb == "damage" }.tick
        val r = ribbons(calls)
        // glow + core per link, 4 links
        assertThat(r).describedAs(r.toString()).hasSize(8)
        assertThat(r.map { it.tick }.distinct()).containsExactly(hit, hit + 3, hit + 6, hit + 9)
        for (s in r) assertThat(verts(s.detail)).`as`(s.detail).hasSize(r.indexOf(s).let { if (it < 2) 9 else 8 })
        // first link starts at the wand (near the caster), hops chain victim to victim
        assertThat(verts(r[0].detail).first().z).isLessThan(3.0)
        val tops = r.filter { it.detail.contains("#eaf6ff") }
        assertThat(tops).hasSize(4)
        val ends = tops.map { verts(it.detail).let { v -> v.first() to v.last() } }
        for (k in 1..3) {
            assertThat(ends[k].first.z).`as`("hop $k from").isBetween(7.0 + 2.0 * (k - 1), 9.0 + 2.0 * (k - 1))
            assertThat(ends[k].second.z).`as`("hop $k to").isBetween(9.0 + 2.0 * (k - 1), 11.0 + 2.0 * (k - 1))
        }
        // the glow hugs the core: same ends
        val glows = r.filter { it.detail.contains("#90d0ff") }
        for ((g, c) in glows.zip(tops)) {
            assertThat(verts(g.detail).first()).isEqualTo(verts(c.detail).first())
            assertThat(verts(g.detail).last()).isEqualTo(verts(c.detail).last())
        }
    }

    @Test
    fun singleVictimNoHop() {
        val calls = run(listOf(victim(1, 8.0)))
        assertThat(calls.filter { it.verb == "damage" }).hasSize(1)
        assertThat(ribbons(calls)).describedAs(ribbons(calls).toString()).hasSize(2)
    }

    @Test
    fun missDrawsNoLine() {
        assertThat(ribbons(run(emptyList()))).isEmpty()
    }
}
