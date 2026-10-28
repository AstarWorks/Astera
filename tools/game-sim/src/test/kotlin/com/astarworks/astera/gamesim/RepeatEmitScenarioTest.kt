package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-14: a v1 particle element / rig emitter that spawned every N ticks over a span converts to a timeline row
 * `{at, every, for}`. Orrery S1 (crescent A and B) keeps an ember emitter on the staff for 9 ticks each (v1 start_t..end_t inclusive); the real runner
 * must fire it every tick (path-filled), not once.
 */
class RepeatEmitScenarioTest {

    @Test
    fun `orrery-s1 staff embers emit once per tick for their authored span, not as one burst`() {
        val bursts = ArrayList<ParticleBurst>()
        val gateway = object : ISkillParticleGateway { override fun emit(burst: ParticleBurst) { bursts += burst } }
        CastSim(mapOf(simVictim(1) to Vec3(0.0, 0.0, 3.0)), particleGateway = gateway).cast("orrery-s1", 120)
        val embers = bursts.count { it.particle == "orrery-ember" }
        assertThat(embers).describedAs("two slashes x 9 ticks of embers, path-filled: 43 plays in the v1 vanilla dump (audit/vanilla/v1/orrery-s1)").isEqualTo(43)
    }
}
