package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
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
          - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: follow}
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

    private val base = "id: bolt, op: projectile, origin: muzzle, velocity: \"= look(caster) * 1.0\", radius: 0.4"

    // instance ids are state-qualified (`cast/bolt`, ADR-0303 C2: an entry is (state, tick, op id, ...)); the final state's own End row is not the body's
    private fun end(skill: CompiledSkill): Row = skill.rows().single { it.action is Action.End && it.instance.text == "cast/bolt" }

    @Test
    fun stickUnstickEvents() {
        val s = compile(
            "{$base, for: 30, hit: {targets: enemies, once: true, attach: false}, block: {mode: stick, for: 20}, " +
                "on: {stick: [{id: a, op: sound, sound: \"astera:x\", anchor: caster}], unstick: [{id: b, op: sound, sound: \"astera:y\", anchor: caster}]}}",
        )
        assertTrue(s.hasSub("stick"))
        assertTrue(s.hasSub("unstick"))
        assertEquals(30 + 20, s.sub("end").window.max)
        assertEquals(30, s.sub("stick").window.max)
        assertEquals(1 + 20, s.sub("unstick").window.min)
    }

    @Test
    fun hitPierceTimes() {
        val s = compile("{$base, for: 40, hit: {targets: enemies, once: false, attach: false, times: 3}, block: {mode: despawn}, on: {hit: [{id: h, op: sound, sound: \"astera:x\", anchor: caster}]}}")
        assertEquals(3, s.sub("hit").maxFirings)
        assertTrue(s.sub("hit").branches.none { it.event.value.endsWith(".end") })
        val e = failure("{$base, for: 40, hit: {targets: enemies, once: false, attach: false}, block: {mode: despawn}}")
        assertTrue(e.toString().contains("times"))
    }

    @Test
    fun blockModeFieldsRequired() {
        assertTrue(failure("{$base, for: 40, hit: {targets: enemies, once: true, attach: false}, block: {mode: bounce}}").toString().contains("restitution"))
        assertTrue(failure("{$base, for: 40, hit: {targets: enemies, once: true, attach: false}, block: {mode: stick}}").toString().contains("for"))
        assertTrue(failure("{$base, for: 40, hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn, restitution: 0.6}}").toString().contains("restitution"))
        assertTrue(failure("{$base, for: 40, hit: {targets: enemies, once: true, attach: false}}").toString().contains("block"))
    }

    @Test
    fun unboundedLifeIsALoadError() {
        val tail = "hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn}"
        // neither `for` nor `range`: no finite life, nothing to cap it with
        failure("{$base, $tail}").toString().let { assertTrue(it.contains("no default flight length"), it) }
        // a flight longer than the server cap is refused, not clamped
        failure("{$base, for: 5000, $tail}").toString().let { assertTrue(it.contains("max_life_ticks"), it) }
        // a range the speed never covers within the cap is refused, not left to run to the cap
        assertTrue(failure("{id: bolt, op: projectile, origin: muzzle, velocity: \"= look(caster) * 0.0\", radius: 0.4, range: 10, $tail}").toString().isNotEmpty())
    }

    @Test
    fun untilMaskNeverWidensTheEndWindow() {
        val s = compile("{$base, for: 30, hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn}}")
        assertEquals(1, s.sub("end").window.min)
        assertEquals(30, s.sub("end").window.max)
        val withDisplay = loadInline(
            skill("{$base, for: 30, hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn}}")
                .replace("\n    done: {final: true}", "\n        - {id: slug, op: display, kind: item, item: \"minecraft:iron_nugget\", context: fixed, anchor: bolt, at: 0, until: bolt.end}\n    done: {final: true}"),
        )
        val d = (withDisplay as? Result.Success)?.value ?: error("expected a clean decode, got $withDisplay")
        assertEquals(1, d.sub("end").window.min)
        assertEquals(30, d.sub("end").window.max)
    }

    // ---- E1: `part:` sweeps the capsule along the body's own axis ----------------------------------------------------------

    private val bodyShape = "id: bolt, op: projectile, origin: muzzle, velocity: \"= look(caster) * 1.0\", part: blade_a, for: 30, " +
        "hit: {targets: enemies, once: true, attach: false, shape: {capsule: 0.3, from: root, to: tip}}, " +
        "block: {mode: despawn}, on: {hit: [{id: h, op: sound, sound: \"astera:hit\", anchor: caster}]}"

    private fun hits(z: Double, x: Double): Int {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = listOf(
            HitCandidate(java.util.UUID(0L, 21L), Vec3(x, 0.0, z), 0.0, HitBox(Vec3(x - 0.3, 0.0, z - 0.3), Vec3(x + 0.3, 1.8, z + 0.3)), 1.62),
        )
        play(compile("{$bodyShape}"), seed = 1L, events = emptyList(), ticks = 40, rec = rec)
        return rec.calls.count { it.verb == "sound" && it.payload.startsWith("sound=astera:hit") }
    }

    @Test
    fun bodyCapsuleHitsAlongOwnAxis() {
        assertEquals(1, hits(z = 4.0, x = 0.0)) // on the flight line
        assertEquals(0, hits(z = 4.0, x = 3.0)) // three blocks to the side: the blade capsule never reaches it
    }

    @Test
    fun partNeedsItsPointAndAShape() {
        assertTrue(failure("{${bodyShape.replace("to: tip", "to: nope")}}").toString().contains("part 'blade_a' has no point 'nope'"))
        assertTrue(failure("{${bodyShape.replace("part: blade_a, ", "")}}").toString().contains("frames"))
    }
}
