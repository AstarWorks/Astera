package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Paper [ISkillSoundGateway] (ADR-0301): a sound always plays at a world point with a volume and a pitch. Volume and pitch
 * are clamped to the range [SoundSpec] allows. One instance serves ONE world, the casting entity's ([world] is fixed at construction).
 */
public class PaperSkillSoundGateway(
    private val gateway: IWorldGateway,
    private val world: WorldId,
) : ISkillSoundGateway {

    override fun play(sound: String, at: Vec3, category: String, volume: Double, pitch: Double) {
        val spec = SoundSpec(
            key = sound,
            volume = volume.coerceIn(0.0, SoundSpec.PRACTICAL_MAX_VOLUME),
            pitch = pitch.coerceIn(SoundSpec.MIN_PITCH, SoundSpec.MAX_PITCH),
        )
        gateway.playSound(world, at, spec)
    }
}
