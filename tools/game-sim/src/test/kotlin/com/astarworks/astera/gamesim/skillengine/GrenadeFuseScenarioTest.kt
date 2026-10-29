package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID

/**
 * Thrown grenades bounce and roll off blocks and explode at one fixed fuse tick after the throw (ADR-0303 rev4.12): `block.mode bounce` with `friction`,
 * `hit.reflect`, `block.fused`. The shipped frag / sticky skills are played through the real TablePlayer over a scripted wall (z = 6) and floor (y = 0).
 */
class GrenadeFuseScenarioTest : SkillEngineScenario() {
    private val far = UUID(0L, 99L)

    /** A victim far off the flight: it only makes the blast's `around: spot` rows produce their `damage` call. */
    private val bystander = HitCandidate(far, Vec3(40.0, 0.0, 40.0), 0.0, HitBox(Vec3(39.7, 0.0, 39.7), Vec3(40.3, 1.8, 40.3)), 1.62)

    private class Segment(val tick: Int, val from: Vec3, val to: Vec3)

    private fun shipped(id: String): CompiledSkill {
        val file = contentDir.resolve("skills/$id/skill.yaml")
        return when (val r = compileText("$id/skill.yaml", Files.readString(file))) {
            is Result.Success -> r.value
            is Result.Failure -> error("$id does not compile: ${r.error}")
        }
    }

    /** Wall at z = 6 facing the caster, floor at y = 0; every probed segment is recorded. */
    private fun scene(rec: GatewayRecorder, wall: Boolean = true): List<Segment> {
        val seen = ArrayList<Segment>()
        rec.blockRay = { from, to ->
            seen += Segment(rec.tick, from, to)
            when {
                wall && from.z < 6.0 && to.z >= 6.0 -> {
                    val t = (6.0 - from.z) / (to.z - from.z)
                    BlockHit(Vec3(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, 6.0), Vec3(0.0, 0.0, -1.0), "minecraft:stone")
                }
                from.y > 0.0 && to.y <= 0.0 -> {
                    val t = (from.y - 0.0) / (from.y - to.y)
                    BlockHit(Vec3(from.x + (to.x - from.x) * t, 0.0, from.z + (to.z - from.z) * t), Vec3(0.0, 1.0, 0.0), "minecraft:stone")
                }
                else -> null
            }
        }
        rec.victims = listOf(bystander)
        return seen
    }

    private fun damageTicks(rec: GatewayRecorder): List<Int> = rec.calls.filter { it.verb == "damage" }.map { it.tick }

    @Test
    fun `frag grenade bounces off a wall and explodes at the fixed fuse tick`() {
        val skill = shipped("frag-grenade-throw")
        val rec = GatewayRecorder()
        val seen = scene(rec)
        play(skill, 7L, emptyList(), 80, rec)

        val first = seen.first()
        assertThat(first.to.z).describedAs("the throw flies toward +z, at the wall").isGreaterThan(first.from.z)
        val hit = seen.indexOfFirst { it.to.z >= 6.0 }
        assertThat(hit).describedAs("the body reaches the wall").isGreaterThanOrEqualTo(0)
        // after the wall the body travels back (z falls) and never passes through it again
        val after = seen.drop(hit + 1).filter { it.tick < 40 }
        assertThat(after).isNotEmpty
        assertThat(after.first().to.z).describedAs("reflected off the wall").isLessThan(after.first().from.z)
        assertThat(seen.drop(hit + 1).none { it.from.z > 6.0 }).describedAs("never behind the wall").isTrue()
        // it keeps flying (bouncing / rolling) until the fuse: probes continue to the last flight tick
        assertThat(seen.maxOf { it.tick }).isGreaterThanOrEqualTo(55)
        // the blast: one damage call, on the fuse tick (fuse_ticks = 60), wherever the body is
        assertThat(damageTicks(rec)).containsExactly(60)
    }

    @Test
    fun `fuse tick does not depend on where the grenade lands`() {
        val skill = shipped("frag-grenade-throw")
        val withWall = GatewayRecorder().also { scene(it) }
        val open = GatewayRecorder().also { scene(it, wall = false) }
        play(skill, 7L, emptyList(), 80, withWall)
        play(skill, 7L, emptyList(), 80, open)
        assertThat(damageTicks(withWall)).containsExactly(60)
        assertThat(damageTicks(open)).containsExactly(60)
    }

    @Test
    fun `sticky bomb sticks to the wall and still explodes at the fuse tick`() {
        val skill = shipped("sticky-bomb-throw")
        val rec = GatewayRecorder()
        val seen = scene(rec)
        play(skill, 7L, emptyList(), 80, rec)

        val stuckAt = seen.firstOrNull { it.to.z >= 6.0 }?.tick ?: error("the sticky bomb never reached the wall")
        assertThat(stuckAt).isLessThan(30)
        assertThat(seen.filter { it.tick > stuckAt }).describedAs("a stuck body probes no more blocks").isEmpty()
        assertThat(damageTicks(rec)).containsExactly(60)
    }

    @Test
    fun `smoke bomb and sticky bomb casts end at the fuse, not at the 1200 cap`() {
        for ((id, fuse) in listOf("smoke-bomb-throw" to 50, "sticky-bomb-throw" to 60, "frag-grenade-throw" to 60, "flash-bang-throw" to 50)) {
            val max = shipped(id).table.bounds.path.max
            assertThat(max).describedAs("$id cast length").isBetween(fuse, fuse + 3)
        }
    }

    private fun inline(block: String, hit: String = "{targets: enemies, once: true, attach: false}"): String = """
        apiVersion: astera/v3
        kind: Skill
        id: fuse-probe
        item: couture-rapier
        meta: {cooldown_ticks: 60, resource_cost: 0}
        anchors:
          - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: follow}
        machine:
          start: cast
          states:
            cast:
              for: 60
              then: done
              do:
                - {id: bolt, op: projectile, origin: muzzle, velocity: "= look(caster) * 1.0", radius: 0.4, for: 40, hit: $hit, block: $block}
            done: {final: true}
    """.trimIndent()

    private fun rejects(yaml: String, needle: String) {
        val r = loadInline(yaml)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error.toString()).contains(needle)
    }

    @Test
    fun `the bounce grammar keeps strict load errors`() {
        assertThat(loadInline(inline("{mode: bounce, restitution: 0.4, friction: 0.2, times: 2}"))).isInstanceOf(Result.Success::class.java)
        rejects(inline("{mode: bounce, restitution: 0.4, times: 2}"), "friction")
        rejects(inline("{mode: bounce, restitution: 1.4, friction: 0.2, times: 2}"), "between 0 and 1")
        rejects(inline("{mode: despawn, friction: 0.2}"), "friction")
        rejects(inline("{mode: stick, fused: true, for: 10}"), "exclusive")
        rejects(inline("{mode: stick}"), "fused")
        rejects(inline("{mode: bounce, restitution: 0.4, friction: 0.2, times: 2, wobble: 1}"), "wobble")
        rejects(inline("{mode: despawn}", "{targets: enemies, once: true, attach: false, reflect: true}"), "reflect")
    }
}
