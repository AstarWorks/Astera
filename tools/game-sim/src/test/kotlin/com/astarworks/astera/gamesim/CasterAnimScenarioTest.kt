package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** SKL-13 (ADR-0301): `op: caster_anim` plays the shipped `content/animations` clip at the authored tick and stops it when the cast is cancelled. */
class CasterAnimScenarioTest {

    private fun sim() = CastSim(mapOf(simVictim(1) to Vec3(0.0, 0.0, 6.0)))

    private fun plays(sim: CastSim) = sim.casterAnims.filter { it.verb == "play" }.map { it.tick to it.anim }

    @Test
    fun `coin 2 plays the couture body clip on tick 0 and a cancelled cast stops it`() {
        val sim = sim()
        val cast = sim.cast("couture-rapier-s2", 10)
        assertThat(plays(sim)).containsExactly(0 to "couture-s2-body")
        assertThat(sim.casterAnims.filter { it.verb != "play" }).isEmpty()

        sim.kit.runner.cancel(cast)
        assertThat(sim.casterAnims.map { it.verb to it.anim }).containsExactly("play" to "couture-s2-body", "stop" to "couture-s2-body")
    }

    @Test
    fun `the sword swing demo plays its clip once and releases it when the cast ends`() {
        val sim = sim()
        sim.cast("anim-demo-sword-swing", 40)
        assertThat(plays(sim)).containsExactly(0 to "sword-swing")
        assertThat(sim.casterAnims.map { it.verb }).containsExactly("play", "release")
    }

    @Test
    fun `bastion greatsword S1 and S2 play their body clips on tick 0`() {
        for ((skill, clip) in listOf("bastion-greatsword-s1" to "bastion-s1-body", "bastion-greatsword-s2" to "bastion-s2-body")) {
            val sim = sim()
            sim.cast(skill, 3)
            assertThat(plays(sim)).describedAs(skill).containsExactly(0 to clip)
        }
    }

    @Test
    fun `every caster_anim in a shipped skill names a content animation or a pose-only clip`() {
        val skillsDir = Coin2CastWiring.collaborators().skillsDir
        val animations = skillsDir.resolveSibling("animations")
        val known = Files.list(animations).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".yaml") }.map { it.removeSuffix(".yaml") }.toList() }.toSet() +
            setOf("crouch", "hide-main-hand", "swing-main-hand")
        val used = Files.walk(skillsDir).use { s ->
            s.filter { it.toString().endsWith(".yaml") }.toList().flatMap { f ->
                Regex("""caster_anim, anim: ([\w-]+)""").findAll(Files.readString(f)).map { it.groupValues[1] }.toList()
            }
        }
        assertThat(used).isNotEmpty
        assertThat(known).containsAll(used)
    }
}
