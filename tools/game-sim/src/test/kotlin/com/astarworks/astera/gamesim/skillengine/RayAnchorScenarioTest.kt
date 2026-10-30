package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import org.assertj.core.api.Assertions.assertThat
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import org.junit.jupiter.api.Test
import java.util.UUID
import java.nio.file.Files

/**
 * The `ray` anchor (ADR-0303 3.3) and the `input.release` event (3.2) through the shipped trial skill `prism-lance`: its `tip` anchor is
 * re-read from the caster's look every tick (it follows a yaw change), sits on the first block the ray meets, and the channel ends at the
 * release, never later than its fixed maximum of 60 ticks.
 */
class RayAnchorScenarioTest : SkillEngineScenario() {
    private fun lance(): CompiledSkill {
        val file = contentDir.resolve("skills/prism-lance/skill.yaml")
        return when (val r = compileText("skill.yaml", Files.readString(file))) {
            is Result.Success -> r.value
            is Result.Failure -> error("prism-lance did not load: ${r.error.message}")
        }
    }

    /** Where the `spark` particle (it rides the `tip` anchor) was emitted, by tick. */
    private fun sparks(rec: GatewayRecorder) = rec.emittedAt.filter { it.second == "electric_spark" }.map { it.first to it.third }

    @Test
    fun aimSitsOnTheFirstBlockAndFollowsAYawChange() {
        val rec = GatewayRecorder().also { it.faithfulPose = true }
        rec.blockRaycast = { _, from, to -> if (to.z > 10.0 && from.z < 10.0 && abs(to.x - from.x) < 1.0) BlockHit(Vec3(from.x, from.y, 10.0), Vec3(0.0, 0.0, -1.0), "stone") else null }
        play(lance(), seed = 1L, events = emptyList(), ticks = 20, rec = rec, yawDeg = 0.0) { now -> if (now == 10) rec.casterYawDeg = 90.0 }
        val before = sparks(rec).filter { it.first in 2..9 }.map { it.second }
        val after = sparks(rec).filter { it.first >= 12 }.map { it.second }
        assertThat(before).isNotEmpty()
        before.forEach { assertThat(it.z).`as`("aim before the turn rests on the block at z=10").isBetween(9.5, 10.5) }
        assertThat(after).isNotEmpty()
        after.forEach {
            assertThat(abs(it.x)).`as`("aim after a 90 degree turn is 24 blocks to the side").isBetween(23.0, 25.0)
            assertThat(abs(it.z)).isLessThan(1.5)
        }
    }

    @Test
    fun releaseCutsTheChannelAndTheEndNeverPassesTheMaximum() {
        val held = GatewayRecorder().also { it.faithfulPose = true }
        play(lance(), seed = 1L, events = emptyList(), ticks = 70, rec = held)
        val heldLast = sparks(held).maxOf { it.first }
        assertThat(heldLast).`as`("a held lance burns to the fixed maximum").isBetween(50, 60)

        val cut = GatewayRecorder().also { it.faithfulPose = true }
        play(lance(), seed = 1L, events = listOf(event(EventPath("input.release"), 12)), ticks = 70, rec = cut)
        val cutLast = sparks(cut).maxOf { it.first }
        assertThat(cutLast).`as`("release at tick 12 ends the channel").isLessThanOrEqualTo(12)
        assertThat(cutLast).isLessThanOrEqualTo(heldLast)
    }

    private fun lanceWith(stops: String): CompiledSkill {
        val text = Files.readString(contentDir.resolve("skills/prism-lance/skill.yaml")).replace("ray_stops: entities", "ray_stops: $stops")
        return when (val r = compileText("skill.yaml", text)) {
            is Result.Success -> r.value
            is Result.Failure -> error("prism-lance ($stops) did not load: ${r.error.message}")
        }
    }

    private fun body(id: UUID, z0: Double) = HitCandidate(id, Vec3(0.0, 0.0, z0), 0.0, HitBox(Vec3(-0.3, 0.0, z0), Vec3(0.3, 1.8, z0 + 0.6)), 1.62)

    /** Where the `tip` anchor rests (z of the sparks from tick 2 on) with a wall at z=10 and the given bodies on the ray. */
    private fun tipZ(skill: CompiledSkill, setup: (GatewayRecorder) -> Unit): List<Double> {
        val rec = GatewayRecorder().also { it.faithfulPose = true }
        rec.blockRaycast = { _, from, to -> if (to.z > 10.0 && from.z < 10.0) BlockHit(Vec3(from.x, from.y, 10.0), Vec3(0.0, 0.0, -1.0), "stone") else null }
        setup(rec)
        play(skill, seed = 1L, events = emptyList(), ticks = 12, rec = rec)
        return sparks(rec).filter { it.first >= 2 }.map { it.second.z }.also { assertThat(it).isNotEmpty() }
    }

    @Test
    fun blocksOnlyRayPassesThroughAnEnemyAndLandsOnTheWall() {
        val z = tipZ(lanceWith("blocks")) { it.victims = listOf(body(UUID(0L, 21L), 5.0)) }
        z.forEach { assertThat(it).`as`("blocks-only ray ignores the enemy at z=5").isBetween(9.5, 10.5) }
    }

    @Test
    fun entitiesRayStopsWhereItEntersTheEnemyHitbox() {
        val z = tipZ(lanceWith("entities")) { it.victims = listOf(body(UUID(0L, 21L), 5.0)) }
        z.forEach { assertThat(it).`as`("entities ray lands on the enemy's front face at z=5").isBetween(4.9, 5.1) }
    }

    @Test
    fun theNearestEnemyWinsAndAnAllyOrTheCasterNeverStopsTheRay() {
        val near = UUID(0L, 22L)
        val ally = UUID(0L, 23L)
        val nearest = tipZ(lanceWith("entities")) { it.victims = listOf(body(UUID(0L, 21L), 7.0), body(near, 3.0)) }
        nearest.forEach { assertThat(it).isBetween(2.9, 3.1) }
        val skipped = tipZ(lanceWith("entities")) { rec ->
            rec.allies += ally
            rec.victims = listOf(body(ally, 3.0), body(rec.caster, 0.0))
        }
        skipped.forEach { assertThat(it).`as`("ally and caster hitboxes on the ray do not stop it").isBetween(9.5, 10.5) }
    }

    @Test
    fun rayStopsIsRequiredWithRayAndForbiddenWithout() {
        val base = Files.readString(contentDir.resolve("skills/prism-lance/skill.yaml"))
        val missing = compileText("skill.yaml", base.replace("ray_stops: entities, ", ""))
        assertThat(missing).isInstanceOf(Result.Failure::class.java)
        val orphan = compileText("skill.yaml", base.replace("{id: muzzle, anchor: caster.eye,", "{id: muzzle, ray_stops: blocks, anchor: caster.eye,"))
        assertThat(orphan).isInstanceOf(Result.Failure::class.java)
    }

    private fun abs(d: Double): Double = kotlin.math.abs(d)
}
