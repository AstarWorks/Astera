package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Paper [ISkillParticleGateway] (ADR-0301). Each particle is emitted with its own velocity (count 0 + offset = direction,
 * extra 1), so a burst can fan inside a cone and inherit the velocity of the point it was emitted from. The sequence is
 * deterministic from [ParticleBurst.seed]. Particle delivery is the shared [IWorldGateway.spawnParticle].
 *
 * The port carries no world; [worldId] supplies the world of the cast being ticked.
 */
public class PaperSkillParticleGateway(
    private val world: IWorldGateway,
    private val worldId: () -> WorldId?,
) : ISkillParticleGateway {

    override fun emit(burst: ParticleBurst) {
        val w = worldId() ?: return
        val color = burst.colorHex?.let(::color)
        val rng = Random(burst.seed)
        val axis = burst.dir?.normalize()?.takeIf { it.lengthSquared() > 0.0 }
        val count = burst.count.coerceIn(0, MAX_PER_BURST)
        repeat(count) {
            val direction = when {
                axis != null -> cone(axis, burst.spreadDeg, rng)
                burst.speed > 0.0 -> sphere(rng)
                else -> Vec3.ZERO
            }
            val velocity = direction * burst.speed + (burst.inheritVelocity ?: Vec3.ZERO)
            val spec = if (velocity.lengthSquared() == 0.0) {
                ParticleSpec(burst.particle, count = 1, color = color)
            } else {
                ParticleSpec(burst.particle, count = 0, offset = velocity, extra = 1.0, color = color)
            }
            world.spawnParticle(w, burst.at, spec)
        }
    }

    /** Uniform direction within [spreadDeg] of the unit [axis]. */
    private fun cone(axis: Vec3, spreadDeg: Double, rng: Random): Vec3 {
        if (spreadDeg <= 0.0) return axis
        val maxTan = tan(Math.toRadians(spreadDeg.coerceAtMost(179.0) / 2.0))
        val r = maxTan * kotlin.math.sqrt(rng.nextDouble())
        val phi = rng.nextDouble() * 2.0 * Math.PI
        val helper = if (kotlin.math.abs(axis.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val a = axis.cross(helper).normalize()
        val b = axis.cross(a)
        return (axis + a * (r * cos(phi)) + b * (r * sin(phi))).normalize()
    }

    private fun sphere(rng: Random): Vec3 {
        val z = rng.nextDouble() * 2.0 - 1.0
        val phi = rng.nextDouble() * 2.0 * Math.PI
        val r = kotlin.math.sqrt(1.0 - z * z)
        return Vec3(r * cos(phi), z, r * sin(phi))
    }

    private fun color(hex: String): ParticleColor? {
        val rgb = hex.removePrefix("#").take(6).toIntOrNull(16) ?: return null
        return ParticleColor((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    private companion object {
        const val MAX_PER_BURST = 64
    }
}
