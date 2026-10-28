package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-08 fallout (ADR-0301): shipped projectile skills must hit thin targets and attribute damage to themselves.
 * The victim boxes here are real-sized (0.6 wide player, 1 block pillar), unlike the generous smoke-test boxes.
 *
 * - `fire-staff` (flame-burst, 1.4 blocks/tick) used to step over a player 4 blocks away: a point-sampled hit sphere
 *   with samples at 3.3 and 4.7 never touches the +-0.6 band around 4.0 (First 10 minutes: staff never killed).
 * - `pistol-shot` (3.2 blocks/tick) used to step over a 1 block structure (Annihilation pillar: HP stayed 450).
 * - every `damage` carries the casting skill id, not an anonymous one (kill feed, replay, tutorials).
 */
class ProjectileHitsScenarioTest {

    @Test
    fun `fire-staff flame burst hits a player-sized victim 4 blocks ahead and is credited to flame-burst`() {
        val victim = simVictim(1)
        val sim = CastSim(mapOf(victim to Vec3(0.0, 0.0, 4.0)), halfWidth = 0.3)
        sim.cast("flame-burst", 40)
        assertThat(sim.dealt.map { it.victim }).describedAs("flame-burst damage").contains(victim)
        assertThat(sim.dealt.filter { it.victim == victim }.map { it.skill }.toSet()).containsExactly("flame-burst")
    }

    @Test
    fun `pistol shot damages a one block wide structure standing in its line`() {
        val pillar = simVictim(2)
        // The sim box is 0 .. boxHeight tall, so a 2 block box covers the 1.62 eye height of the muzzle.
        val sim = CastSim(mapOf(pillar to Vec3(0.0, 0.0, 6.0)), halfWidth = 0.5, boxHeight = 2.0)
        sim.cast("pistol-shot", 30)
        assertThat(sim.dealt.filter { it.victim == pillar }).describedAs("pistol-shot damage on the structure").isNotEmpty()
        assertThat(sim.dealt.map { it.skill }.toSet()).containsExactly("pistol-shot")
    }

    @Test
    fun `pistol shot hits a zombie-sized mob 10 blocks away and a cave-spider-sized one aimed low`() {
        val zombie = simVictim(3)
        val sim = CastSim(mapOf(zombie to Vec3(0.0, 0.0, 10.0)), halfWidth = 0.3, boxHeight = 1.95)
        sim.cast("pistol-shot", 40)
        assertThat(sim.dealt.filter { it.victim == zombie }).describedAs("pistol-shot damage on a zombie 10 blocks ahead").isNotEmpty()

        // A cave spider is 0.5 tall: the level eye-height shot (1.62) flies over it, the PvE e2e must aim at its body (y + 0.4).
        val spider = simVictim(4)
        val level = CastSim(mapOf(spider to Vec3(0.0, 0.0, 10.0)), halfWidth = 0.35, boxHeight = 0.5)
        level.cast("pistol-shot", 40)
        assertThat(level.dealt.filter { it.victim == spider }).describedAs("a level shot misses a 0.5 tall mob").isEmpty()
    }
}
