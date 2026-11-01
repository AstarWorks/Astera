package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/**
 * The Annihilation navigation compass draws a particle trail toward its target that only the holder sees (per-viewer spawn:
 * [Audience.CASTER] with the holder as source). Proven on the real `content/`: the holder gets the dotted path, a second player
 * of the match gets none, nothing is drawn once the compass is not held, and the trail ends before the target.
 */
class AnnihilationCompassTrailScenarioTest {

    private val config = com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig(
        pillarHp = 1000,
        buildPhaseTicks = 4_000,
        engagePhaseTicks = 4_000,
        centerPhaseTicks = 4_000,
        escalationPhaseTicks = 200,
        suddenDeathTicks = 400,
        bleedIntervalTicks = 20,
        bleedPercent = 10,
        bleedFloorPercent = 10,
    )

    private fun ScenarioScope.trail(who: SimEntity) =
        sim.recorder.particleCalls().filter { it.spec.key == "minecraft:end_rod" && it.spec.viewers == Audience.CASTER && it.spec.source == who.id }

    @Test
    fun `only the compass holder sees the trail, it stops when not held and ends near the target`() {
        scenario("annihilation-compass-trail", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)
            advance(30)

            // Not held: no trail at all.
            advance(40)
            assertThat(sim.recorder.particleCalls().filter { it.spec.key == "minecraft:end_rod" && it.spec.source != null }).isEmpty()

            // Held: a dotted path of several points, per-viewer to the holder only, and nothing for the others.
            sim.matchCompass.select(red.id, true)
            advance(25)
            val target = checkNotNull(sim.hologramGuide.guideOf(red.id)).target
            val before = trail(red).size
            advance(40)
            val drawn = trail(red).drop(before)
            assertThat(drawn.size).`as`("four 10-tick draws of about 6-10 points").isBetween(24, 40)
            assertThat(drawn.map { it.tick }.distinct().size).isGreaterThanOrEqualTo(3)
            assertThat(trail(blue)).isEmpty()
            assertThat(trail(yellow)).isEmpty()
            assertThat(sim.recorder.particleCalls().filter { it.spec.key == "minecraft:end_rod" && it.spec.viewers == Audience.ALL }).isEmpty()
            val first = drawn.first()
            assertThat(hypot(first.at.x - red.position.x, first.at.z - red.position.z)).`as`("starts at the holder").isLessThan(2.0)

            // Let go of the compass: nothing more is sent.
            sim.matchCompass.select(red.id, false)
            advance(5)
            val afterRelease = trail(red).size
            advance(60)
            assertThat(trail(red).size).`as`("no trail once the compass is not held").isEqualTo(afterRelease)

            // Held again but standing next to the target: the trail has faded out.
            sim.matchCompass.select(red.id, true)
            red.position = Vec3(target.x + 2.0, groundY, target.z)
            advance(25)
            val near = trail(red).size
            advance(40)
            assertThat(trail(red).size).`as`("nothing is drawn within a few blocks of the target").isEqualTo(near)
        }
    }
}
