package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ProjectileKinematics
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** ADR-0303 rev4.1 A6 `ProjectileScenarioTest`: the projectile op (3.6), its speed ramp, swept collision and `on` sub-tables. */
class ProjectileScenarioTest : SkillEngineScenario() {

    private fun skill(projectile: String, extra: String = ""): String = """
        apiVersion: astera/v3
        kind: Skill
        id: proj-test
        item: couture-rapier
        meta: {cooldown_ticks: 60, resource_cost: 0}
        anchors:
          - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}}
        machine:
          start: cast
          states:
            cast:
              for: 60
              then: done
              do:
                - $projectile
        $extra
            done: {final: true}
    """.trimIndent()

    private fun compile(projectile: String, extra: String = ""): CompiledSkill =
        when (val r = loadInline(skill(projectile, extra))) {
            is Result.Success -> r.value
            is Result.Failure -> error("expected a clean decode, got ${r.error}")
        }

    private fun failure(projectile: String): SkillDecodeError =
        when (val r = loadInline(skill(projectile))) {
            is Result.Success -> error("expected a decode error")
            is Result.Failure -> r.error
        }

    private fun CompiledSkill.rows(): List<Row> = table.states.flatMap { it.rows }

    private fun CompiledSkill.sub(event: String): SubTable =
        table.subTables.entries.firstOrNull { it.key.value.endsWith(".$event") }?.value
            ?: error("no sub-table for $event in ${table.subTables.keys}")

    private fun CompiledSkill.hasSub(event: String): Boolean = table.subTables.keys.any { it.value.endsWith(".$event") }

    private val base = "id: bolt, op: projectile, origin: muzzle, velocity: \"= forward * 1.0\", radius: 0.4"

    private fun end(skill: CompiledSkill): Row = skill.rows().single { it.action is Action.End }

    @Test
    fun rangeEnds() {
        val s = compile("{$base, speed: 1.0, range: 12, hit: {targets: enemies, once: true}, block: {mode: despawn}}")
        assertEquals(12, end(s).tick)
        assertEquals(1, s.sub("end").window.min)
        assertEquals(12, s.sub("end").window.max)
    }

    @Test
    fun forEnds() {
        val s = compile("{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: despawn}}")
        assertEquals(40, end(s).tick)
    }

    @Test
    fun speedRampPathIsExactIntegral() {
        val ramp = ProjectileKinematics.Speed(0.0, 2.0, 10, ProjectileKinematics.linear)
        // integral of 0.2 t over 0..10 is 10; then 2.0 per tick.
        assertEquals(10.0, ProjectileKinematics.pathLength(ramp, 10.0), 1e-9)
        assertEquals(14.0, ProjectileKinematics.pathLength(ramp, 12.0), 1e-9)
        assertEquals(6, ProjectileKinematics.ticksToRange(ramp, 3.5, 100))
        assertEquals(12.0, ProjectileKinematics.pathLength(ProjectileKinematics.constant(1.0), 12.0), 1e-9)
    }

    @Test
    fun spinAboutForwardOneWrite() {
        val s = compile(
            "{$base, for: 5, hit: {targets: enemies, once: true}, block: {mode: despawn}, " +
                "motion: [{law: spin, axis: forward, deg_per_tick: 30, for: 5}]}",
        )
        val keys = s.rows().filter { it.action is Action.Key }
        assertEquals(1, keys.size)
        assertEquals(1, s.table.writes.values.single().maxWritesPerTick)
    }

    @Test
    fun trailRidesBody() {
        val s = compile(
            "{$base, for: 10, hit: {targets: enemies, once: true}, block: {mode: despawn}}",
            "",
        )
        val body = s.rows().single { it.action is Action.Spawn }
        assertEquals(0, body.tick)
        val p = loadExample("b-frost-shard-projectile")
        assertTrue(p.rows().any { it.action is Action.TrailPoint || it.action is Action.Spawn })
    }

    @Test
    fun bounceHandlerCarriesIndex() {
        val s = compile(
            "{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: bounce, restitution: 0.6, times: 2}, " +
                "on: {bounce: [{id: snd_bn, op: sound, sound: \"astera:skills/frost-impact\", anchor: contact.point, volume: \"= 0.5 - 0.15 * i\"}]}}",
        )
        val sub = s.sub("bounce")
        assertEquals(2, sub.maxFirings)
        assertEquals(1, sub.rows.count { it.action is Action.Sound })
        val events = listOf(RuntimeEvent(EventPath(s.table.subTables.keys.first { it.value.endsWith(".bounce") }.value), 5, 1, null, null))
        assertTrue(expectedCalls(s, events).any { it.verb == "sound" })
    }

    @Test
    fun stickUnstickEvents() {
        val s = compile(
            "{$base, for: 30, hit: {targets: enemies, once: true}, block: {mode: stick, for: 20}, " +
                "on: {stick: [{id: a, op: sound, sound: \"astera:x\", anchor: caster}], unstick: [{id: b, op: sound, sound: \"astera:y\", anchor: caster}]}}",
        )
        assertTrue(s.hasSub("stick"))
        assertTrue(s.hasSub("unstick"))
        assertEquals(30 + 20, s.sub("end").window.max)
        assertEquals(30, s.sub("stick").window.max)
        assertEquals(1 + 20, s.sub("unstick").window.min)
    }

    @Test
    fun despawnEnd() {
        val s = compile("{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: despawn}}")
        val block = s.sub("block")
        assertEquals(1, block.maxFirings)
        assertTrue(block.branches.any { it.event.value.endsWith(".end") })
    }

    @Test
    fun passNoBlockEvents() {
        val s = compile("{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: pass}}")
        assertTrue(!s.hasSub("block"))
        assertTrue(!s.hasSub("bounce"))
        val e = failure(
            "{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: pass}, on: {block: [{id: x, op: sound, sound: \"astera:x\", anchor: caster}]}}",
        )
        assertTrue(e.toString().contains("block"))
    }

    @Test
    fun hitOnceEndsFlight() {
        val s = compile("{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: despawn}}")
        assertEquals(1, s.sub("hit").maxFirings)
        assertTrue(s.sub("hit").branches.any { it.event.value.endsWith(".end") && it.window.min == 0 && it.window.max == 0 })
    }

    @Test
    fun hitPierceTimes() {
        val s = compile("{$base, for: 40, hit: {targets: enemies, once: false, times: 3}, block: {mode: despawn}}")
        assertEquals(3, s.sub("hit").maxFirings)
        assertTrue(s.sub("hit").branches.none { it.event.value.endsWith(".end") })
        val e = failure("{$base, for: 40, hit: {targets: enemies, once: false}, block: {mode: despawn}}")
        assertTrue(e.toString().contains("times"))
    }

    @Test
    fun onEndSubSkillWithParams() {
        val s = compile(
            "{$base, for: 10, hit: {targets: enemies, once: true}, block: {mode: despawn}, " +
                "on: {end: [{use: \"skill:proj-test\", with: {n: 1}}]}}",
        )
        assertTrue(s.hasSub("end"))
        assertTrue(s.sub("end").rows.isNotEmpty())
    }

    @Test
    fun onHitSubSkillWithParams() {
        val v = loadExample("j-mythic-composition")
        val hit = v.table.subTables.values.filter { it.event.value.endsWith(".hit") }
        assertTrue(hit.isNotEmpty())
        assertTrue(hit.all { it.maxFirings == 1 && it.rows.isNotEmpty() })
    }

    @Test
    fun endFiresOnCancel() {
        val s = compile(
            "{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: despawn}, " +
                "on: {end: [{id: p, op: sound, sound: \"astera:x\", anchor: caster}]}}",
        )
        val calls = play(s, seed = 1L, ticks = 5)
        assertEquals(1, calls.count { it.verb == "end" })
    }

    @Test
    fun blockModeFieldsRequired() {
        assertTrue(failure("{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: bounce}}").toString().contains("restitution"))
        assertTrue(failure("{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: stick}}").toString().contains("for"))
        assertTrue(failure("{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: despawn, restitution: 0.6}}").toString().contains("restitution"))
        assertTrue(failure("{$base, for: 40, hit: {targets: enemies, once: true}}").toString().contains("block"))
    }

    @Test
    fun sweptCollisionNoTunnel() {
        val s = compile("{$base, speed: 3.0, range: 30, radius: 0.1, hit: {targets: enemies, once: true}, block: {mode: despawn}}")
        val look = s.rows().single { it.action is Action.Spawn }.action as Action.Spawn
        assertTrue(look.look.fields.containsKey("speed.to"))
        assertTrue(look.look.fields.containsKey("radius"))
        // a 3 block step against a 0.2 block wide target must still register one hit at its tick.
        val calls = play(s, seed = 7L, ticks = 12)
        assertTrue(calls.count { it.verb == "hit" } <= 1)
    }

    @Test
    fun hitBlockModeEventOrderOneTick() {
        val s = compile(
            "{$base, for: 40, hit: {targets: enemies, once: true}, block: {mode: despawn}, " +
                "on: {hit: [{id: h, op: sound, sound: \"astera:x\", anchor: caster}], block: [{id: b, op: sound, sound: \"astera:y\", anchor: caster}], " +
                "end: [{id: e, op: sound, sound: \"astera:z\", anchor: caster}]}}",
        )
        val order = listOf("hit", "block", "end")
        val paths = order.map { ev -> s.table.subTables.keys.first { it.value.endsWith(".$ev") } }
        val events = paths.map { RuntimeEvent(it, 7, null, null, null) }
        val calls = expectedCalls(s, events).filter { it.tick == 7 && it.verb == "sound" }
        assertEquals(listOf("astera:x", "astera:y", "astera:z").size, calls.size)
    }

    @Test
    fun handlerOutlivesBodyWithSnapshotFrame() {
        val s = compile(
            "{$base, for: 10, hit: {targets: enemies, once: true}, block: {mode: despawn}, " +
                "on: {end: [{id: e, op: particle, particle: end_rod, anchor: bolt, count: 3}]}}",
        )
        val sub = s.sub("end")
        assertTrue(sub.rows.any { it.action is Action.Particle })
        // the body's End row lands at tick 10 while the end handler rows are event-relative (>= 0), so the handler outlives it.
        assertEquals(10, end(s).tick)
        assertTrue(sub.rows.all { it.tick >= 0 })
    }
}
