package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * ADR-0312: a `particle` op writes its vanilla `offset` in the anchor's frame axes `{forward, up, left}`. A direction (count 0) is resolved
 * against the anchor's axes at every emission tick and sent as the vanilla world direction (1:1 with `spawnParticle`); a spread box
 * (count > 0) is allowed only as a cube, which no rotation changes; anything else fails loudly at load.
 */
class ParticleFrameBasisScenarioTest : SkillEngineScenario() {
    private fun skill(op: String): String = """
        apiVersion: astera/v3
        kind: Skill
        id: particle-frame-probe
        anchors:
          - {id: body, anchor: caster, mode: follow}
        machine:
          start: s
          states:
            s:
              for: 20
              do:
                - id: fx0
                  at: 0
                  do:
                    - $op
    """.trimIndent() + "\n"

    private fun loaded(op: String): CompiledSkill {
        val r = loadInline(skill(op))
        assertThat(r).`as`("expected a load, got $r").isInstanceOf(Result.Success::class.java)
        return (r as Result.Success).value
    }

    private fun failure(op: String): SkillDecodeError {
        val r = loadInline(skill(op))
        assertThat(r).`as`("expected a load failure, got $r").isInstanceOf(Result.Failure::class.java)
        return (r as Result.Failure).error
    }

    private fun burstsAt(skill: CompiledSkill, yawDeg: Double, ticks: Int = 4, beforeTick: (GatewayRecorder, Int) -> Unit = { _, _ -> }): List<Pair<Int, Vec3>> {
        val rec = GatewayRecorder().also { it.faithfulPose = true }
        play(skill, seed = 1L, events = emptyList(), ticks = ticks, rec = rec, yawDeg = yawDeg) { now -> beforeTick(rec, now) }
        return rec.bursts.map { (tick, b) ->
            assertThat(b.count).`as`("a direction burst is vanilla count 0").isEqualTo(0)
            tick to b.offset
        }
    }

    private val direction = "{id: dir, op: particle, particle: end_rod, count: 0, extra: 0.4, anchor: body, offset: {forward: 1.0, up: 0.5, left: 0.25}}"

    @Test
    fun frameDirectionFollowsTheCasterAtTwoYaws() {
        val s = loaded(direction)
        // yaw 0 faces +Z with +X on the caster's left; yaw 90 faces -X with +Z on the left (Minecraft yaw)
        val at0 = burstsAt(s, 0.0).single().second
        assertThat(at0.x).isCloseTo(0.25, within(1e-6))
        assertThat(at0.y).isCloseTo(0.5, within(1e-6))
        assertThat(at0.z).isCloseTo(1.0, within(1e-6))
        val at90 = burstsAt(s, 90.0).single().second
        assertThat(at90.x).isCloseTo(-1.0, within(1e-6))
        assertThat(at90.y).isCloseTo(0.5, within(1e-6))
        assertThat(at90.z).isCloseTo(0.25, within(1e-6))
    }

    @Test
    fun frameDirectionIsResolvedAgainstTheAxesOfEachEmissionTick() {
        val s = loaded("{id: dir, op: particle, particle: end_rod, count: 0, extra: 0.4, anchor: body, offset: {forward: 1.0}, times: 3, every: 5}")
        val rows = burstsAt(s, 0.0, ticks = 16) { rec, now -> if (now == 7) rec.casterYawDeg = 90.0 }
        assertThat(rows).hasSize(3)
        val (first, second, third) = rows.sortedBy { it.first }.map { it.second }
        assertThat(first.z).`as`("before the turn the direction is +Z").isCloseTo(1.0, within(1e-6))
        assertThat(second.z).`as`("tick 5 is still before the turn at tick 7").isCloseTo(1.0, within(1e-6))
        assertThat(third.x).`as`("the tick-10 emission is resolved against the turned axes: -X").isCloseTo(-1.0, within(1e-6))
    }

    @Test
    fun cubeSpreadInTheFrameIsTheVanillaCube() {
        val rec = GatewayRecorder().also { it.faithfulPose = true }
        play(
            loaded("{id: puff, op: particle, particle: flame, count: 6, anchor: body, offset: {forward: 0.3, up: 0.3, left: 0.3}}"),
            seed = 1L, events = emptyList(), ticks = 4, rec = rec, yawDeg = 70.0,
        )
        val b = rec.bursts.single().second
        assertThat(b.count).isEqualTo(6)
        assertThat(listOf(b.offset.x, b.offset.y, b.offset.z)).allSatisfy { assertThat(it).isCloseTo(0.3, within(1e-9)) }
    }

    @Test
    fun aSpreadBoxThatARotationWouldChangeFailsAtLoad() {
        val e = failure("{id: puff, op: particle, particle: flame, count: 6, anchor: body, offset: {forward: 0.5, up: 0.1, left: 0.1}}")
        assertThat(e.message).contains("cannot be rotated into the anchor frame")
    }

    @Test
    fun aFrameOffsetNeedsACountKnownAtLoad() {
        val e = failure("{id: puff, op: particle, particle: flame, count: \"= rand() * 4.0\", anchor: body, offset: {forward: 0.5}}")
        assertThat(e.message).contains("needs a count known at load")
    }
}
