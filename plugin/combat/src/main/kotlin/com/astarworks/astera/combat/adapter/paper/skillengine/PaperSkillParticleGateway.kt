package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleEmitContext
import com.astarworks.astera.core.domain.effect.ParticleEmitter
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Paper [ISkillParticleGateway] (ADR-0301). A vanilla emit is a thin 1:1 mapping onto
 * [IWorldGateway.spawnParticle]: `ParticleSpec(key, count, offset, extra = speed, dust colour / size)` with the op's
 * values untouched (vanilla `count 0` keeps its direction meaning). Nothing is added: an unset field is the vanilla
 * default.
 *
 * The only expansion is the explicit `dir` sugar: `count` spawns of (`count 0`, `offset = jitter(dir, spread) * speed +
 * inherited velocity`, `extra 1`), deterministic from [ParticleBurst.seed]. A custom particle definition
 * ([ParticleEmitter.plan]) owns shape, velocity and vanilla type; the burst's `count`, when set, is the number of
 * particles (replaces the definition's count) and its shape faces `dir`, else the caster look.
 *
 * The port carries no world; [worldId] supplies the world of the cast being ticked.
 */
public class PaperSkillParticleGateway(
    private val world: IWorldGateway,
    private val worldId: () -> WorldId?,
    /** Custom particle lookup (`content/particles/<id>.yaml`); `null` = the id is a vanilla particle key. */
    private val definitions: (String) -> ParticleDefinition? = { null },
) : ISkillParticleGateway {

    override fun emit(burst: ParticleBurst) {
        val w = worldId() ?: return
        val definition = if (burst.particle.matches(CUSTOM_ID)) definitions(burst.particle) else null
        if (definition != null) {
            emitDefinition(w, definition, burst)
            return
        }
        val color = burst.colorHex?.let(::color)
        val scale = burst.dataSize?.toFloat() ?: 1.0f
        val dir = burst.dir
        if (dir == null) {
            world.spawnParticle(w, burst.at, ParticleSpec(burst.particle, count = burst.count ?: 1, offset = burst.offset, extra = burst.speed, color = color, scale = scale))
            return
        }
        val rng = Random(burst.seed)
        val axis = dir.normalize().takeIf { it.lengthSquared() > 0.0 }
        repeat((burst.count ?: 1).coerceIn(0, MAX_PER_BURST)) {
            val direction = if (axis != null) cone(axis, burst.spreadDeg, rng) else Vec3.ZERO
            val velocity = direction * burst.speed + (burst.inheritVelocity ?: Vec3.ZERO)
            world.spawnParticle(w, burst.at, ParticleSpec(burst.particle, count = 0, offset = velocity, extra = 1.0, color = color, scale = scale))
        }
    }

    private fun emitDefinition(w: WorldId, definition: ParticleDefinition, burst: ParticleBurst) {
        val color = burst.colorHex?.let(::color)
        val ctx = ParticleEmitContext(look = burst.dir ?: burst.look, random = kotlin.random.Random(burst.seed))
        for (planned in ParticleEmitter.plan(definition, burst.at, ctx, countOverride = burst.count)) {
            world.spawnParticle(w, planned.at, if (color != null) planned.spec.copy(color = color) else planned.spec)
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

    private fun color(hex: String): ParticleColor? {
        val rgb = hex.removePrefix("#").take(6).toIntOrNull(16) ?: return null
        return ParticleColor((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    private companion object {
        const val MAX_PER_BURST = 64
        /** Custom ids are lower-kebab ([com.astarworks.astera.core.domain.effect.ParticleId]); vanilla keys use underscores. */
        val CUSTOM_ID = Regex("^[a-z][a-z0-9-]*$")
    }
}
