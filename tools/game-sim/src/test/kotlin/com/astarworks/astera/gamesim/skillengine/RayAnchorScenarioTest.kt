package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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

    private fun abs(d: Double): Double = kotlin.math.abs(d)
}
