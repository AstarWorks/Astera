package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Paper [ISkillSoundGateway] (ADR-0299): a sound always plays at a world point with a volume and a pitch. Volume and pitch
 * are clamped to the range [SoundSpec] allows. The port carries no world; [worldId] supplies the world of the cast being ticked.
 */
public class PaperSkillSoundGateway(
    private val world: IWorldGateway,
    private val worldId: () -> WorldId?,
) : ISkillSoundGateway {

    override fun play(sound: String, at: Vec3, volume: Double, pitch: Double) {
        val w = worldId() ?: return
        val spec = SoundSpec(
            key = sound,
            volume = volume.coerceIn(0.0, SoundSpec.PRACTICAL_MAX_VOLUME),
            pitch = pitch.coerceIn(SoundSpec.MIN_PITCH, SoundSpec.MAX_PITCH),
        )
        world.playSound(w, at, spec)
    }
}
