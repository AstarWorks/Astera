package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.contents.ParticleContents
import com.astarworks.astera.core.domain.effect.ParticleEmitContext
import com.astarworks.astera.core.domain.effect.ParticleEmitter
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The shipped gun muzzle flashes (real `content/particles`, real emitter) keep every vanilla flame near the muzzle over its
 * whole life. Vanilla `flame` (RisingParticle: friction 0.96, no gravity, life 12-44 ticks) travels about v / 0.04 blocks, so a
 * `count > 0` flash with `speed 0.08` (gaussian velocity per particle) threw flames 2-5 blocks in any direction and some
 * landed on the floor at the caster's feet (rifle-shot, pistol-shot). The flash is now `count 0` directed forward.
 */
class ShippedMuzzleFlashScenarioTest : SkillEngineScenario() {
    private val friction = 0.96
    private val maxLife = 44

    @Test
    fun muzzleFlashFlamesStayNearTheMuzzle() {
        val particles = ParticleContents().also { it.bootstrap(contentDir.resolve("particles")) }
        val muzzle = Vec3(0.0, 1.35, 0.0)
        val look = Vec3(0.0, 0.0, 1.0)
        for (id in listOf("gun-muzzle-flash", "shotgun-muzzle")) {
            val def = particles.find(ParticleId(id))!!
            // gun-muzzle-flash is a short-life orange dust puff since the caster-linger fix; the flame friction bound below is still conservative for it.
            assertThat(def.particle).isIn("flame", "dust")
            for (seed in 0L until 20L) {
                for (p in ParticleEmitter.plan(def, muzzle, ParticleEmitContext(look = look, random = Random(seed)))) {
                    // vanilla client: count 0 -> velocity = offset * extra; count > 0 -> gaussian * extra per axis (3 sigma bound)
                    val v = if (p.spec.count == 0) p.spec.offset * p.spec.extra else Vec3.ZERO
                    val sigma = if (p.spec.count > 0) 3.0 * p.spec.extra else 0.0
                    var pos = p.at
                    var vel = v
                    var minY = pos.y
                    repeat(maxLife) {
                        pos += vel
                        vel *= friction
                        minY = minOf(minY, pos.y - sigma * (1 - Math.pow(friction, it + 1.0)) / (1 - friction))
                    }
                    val travel = (pos - p.at).length() + sigma / (1 - friction)
                    assertThat(travel).describedAs("$id seed $seed travel").isLessThan(2.5)
                    assertThat(minY).describedAs("$id seed $seed lowest y").isGreaterThan(muzzle.y - 0.5)
                }
            }
        }
    }
}
