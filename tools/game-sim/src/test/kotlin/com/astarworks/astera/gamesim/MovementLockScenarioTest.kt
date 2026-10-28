package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * SKL-11: the additive `lock` op (probe skill) and the v1 windup sound pitch arc on the shipped `bastion-greatsword-ego`.
 */
class MovementLockScenarioTest {

    @Test
    fun `bastion ego windup sound reproduces the v1 in_quad pitch arc`() {
        val sim = CastSim(mapOf(simVictim(1) to Vec3(0.0, 0.0, 2.0)))
        sim.cast("bastion-greatsword-ego", 60)
        val windup = sim.soundPitches.filter { it.second == "block.amethyst_block.resonate" }
        // v1 pitch_arc: 0.6 -> 1.8 over 25 ticks, in_quad, every 5 ticks (pitch values identical); the existing `every` repeat fires one cast tick early (semantic-diff.md).
        assertThat(windup.map { it.first }).containsExactly(0, 4, 9, 14, 19, 24)
        val expected = listOf(0, 5, 10, 15, 20, 25).map { 0.6 + 1.2 * (it / 25.0) * (it / 25.0) }
        windup.map { it.third }.zip(expected).forEach { (got, want) -> assertThat(got).isCloseTo(want, org.assertj.core.data.Offset.offset(1e-9)) }
    }

    private fun probeSim(): CastSim {
        val sim = CastSim(emptyMap())
        val dir = Files.createTempDirectory("lock-probe")
        Files.createDirectories(dir.resolve("lock-probe"))
        Files.writeString(
            dir.resolve("lock-probe/skill.yaml"),
            """
            apiVersion: "astera/v2"
            kind: Skill
            id: lock-probe
            item: knight-sword
            meta:
              name: {ja: "p", en: "p"}
              description: {ja: "p", en: "p"}
              cooldown_ticks: 10
              tags: [melee]
            gameplay:
              timeline:
                - id: t0
                  at: 0
                  do:
                    - {id: lk, op: lock, target: caster, speed: 0.25, ticks: 10}
                - id: t30
                  at: 30
                  do:
                    - {id: wait, op: event, name: done}
            """.trimIndent(),
        )
        val report = SkillEngineContents(sim.catalog).reload(dir)
        assertThat(report.failed).describedAs("probe loads: ${report.failures}").isZero()
        return sim
    }

    @Test
    fun `a lock holds the slow factor for its ticks and then releases the caster`() {
        val sim = probeSim()
        sim.cast("lock-probe", 40)
        assertThat(sim.moveLocks.first()).isEqualTo(0 to 0.25)
        val release = sim.moveLocks.single { it.second == null }
        assertThat(release.first).describedAs("released after 10 locked ticks (0..9)").isBetween(9, 10)
    }

    @Test
    fun `a cancelled cast frees the caster`() {
        val sim = probeSim()
        val cast = sim.cast("lock-probe", 5)
        assertThat(sim.moveLocks.last().second).describedAs("still slowed mid-lock").isEqualTo(0.25)
        sim.kit.runner.cancel(cast)
        assertThat(sim.moveLocks.last().second).describedAs("cancel releases the lock").isNull()
    }
}
