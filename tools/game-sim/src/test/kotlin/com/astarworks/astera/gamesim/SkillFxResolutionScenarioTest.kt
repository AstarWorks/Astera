package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillParticleGateway
import com.astarworks.astera.core.application.contents.ParticleContents
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * SKL-08/09 presentation: the custom particle ids a v2 `look.yaml` names (`gun-muzzle-flash`, `hit-spark`, ...) expand
 * through the real `content/particles` into the vanilla particles the world gateway can spawn, and the cast sound cue
 * id reaches the sound port unchanged. The Paper gateway is the shipped one; only the world delivery is recorded.
 */
class SkillFxResolutionScenarioTest {

    private class RecordingWorld : IWorldGateway by noopWorld() {
        val particles = ArrayList<ParticleSpec>()
        override fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec) { particles += particle }
    }

    private fun gatewayAndWorld(): Pair<PaperSkillParticleGateway, RecordingWorld> {
        val contentDir = Path.of(requireNotNull(System.getProperty("astera.sim.contentDir")) { "astera.sim.contentDir is not set" })
        val registry = ParticleContents().also { check(it.bootstrap(contentDir.resolve("particles")).failed == 0) }
        val world = RecordingWorld()
        return PaperSkillParticleGateway(world, WorldId("world")) { registry.find(ParticleId(it)) } to world
    }

    @Test
    fun `pistol-shot resolves its custom muzzle particles to vanilla ones and plays the gun-shot cue`() {
        val (gateway, world) = gatewayAndWorld()
        val sim = CastSim(mapOf(simVictim(1) to Vec3(0.0, 0.0, 6.0)), particleGateway = gateway)
        sim.cast("pistol-shot", 20)
        val keys = world.particles.map { it.key }
        assertThat(keys).describedAs("gun-muzzle-flash expands to its flame preset").contains("flame")
        assertThat(keys).describedAs("every key is a vanilla particle id, never a content id").allMatch { '-' !in it }
        assertThat(sim.sounds.map { it.second }).contains("astera:weapons/gun-shot")
    }

    @Test
    fun `no shipped skill emits a particle key the world gateway cannot resolve`() {
        val (gateway, world) = gatewayAndWorld()
        val dummies = (1..4).associate { simVictim(it) to Vec3(0.0, 0.0, 2.0 * it) }
        for (id in CastSim(dummies).catalog.ids().filter { '@' !in it }.sorted()) {
            CastSim(dummies, casterStacks = mapOf("charge" to 5, "bleed" to 5, "burn" to 5), particleGateway = gateway).cast(id, 120)
        }
        assertThat(world.particles).isNotEmpty()
        assertThat(world.particles.map { it.key }.filter { '-' in it }.toSet()).describedAs("unresolved custom particle ids").isEmpty()
    }
}

private fun noopWorld(): IWorldGateway = java.lang.reflect.Proxy.newProxyInstance(
    IWorldGateway::class.java.classLoader, arrayOf(IWorldGateway::class.java),
) { _, m, _ ->
    when (m.returnType) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Double.TYPE -> 0.0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
} as IWorldGateway
