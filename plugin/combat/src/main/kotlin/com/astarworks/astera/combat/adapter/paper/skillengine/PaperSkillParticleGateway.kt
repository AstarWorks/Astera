package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.effect.ParticleDefinition

/**
 * Paper [ISkillParticleGateway] (ADR-0301). Every call comes from the pure [toParticleCalls] (shared with the game-sim);
 * a vanilla emit is a thin 1:1 mapping onto
 * [IWorldGateway.spawnParticle]: `ParticleSpec(key, count, offset, extra = speed, dust colour / size)` with the op's
 * values untouched (vanilla `count 0` keeps its direction meaning). Nothing is added: an unset field is the vanilla
 * default.
 *
 * The only expansion is the explicit `dir` sugar: `count` spawns of (`count 0`, `offset = jitter(dir, spread) * speed +
 * inherited velocity`, `extra 1`), deterministic from [ParticleBurst.seed]. A custom particle definition
 * ([ParticleEmitter.plan]) owns shape, velocity and vanilla type; the burst's `count`, when set, is the number of
 * particles (replaces the definition's count) and its shape faces `dir`, else the caster look.
 *
 * The world is [CastRef.world] of each call.
 */
public class PaperSkillParticleGateway(
    private val gateway: IWorldGateway,
    /** Custom particle lookup (`content/particles/<id>.yaml`); `null` = the id is a vanilla particle key. */
    private val definitions: (String) -> ParticleDefinition? = { null },
) : ISkillParticleGateway {

    override fun emit(cast: CastRef, burst: ParticleBurst) {
        for (call in toParticleCalls(burst, definitions)) gateway.spawnParticle(cast.world, call.at, call.spec)
    }
}
