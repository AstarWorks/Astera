package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.SoundCategory

/**
 * Paper [ISkillSoundGateway] (ADR-0301): a sound plays at a world point with a volume, a pitch and a mixer category
 * (a Bukkit `SoundCategory` name: `master`, `music`, `records`, `weather`, `blocks`, `hostile`, `neutral`, `players`,
 * `ambient`, `voice`). Volume and pitch are clamped to the range [SoundSpec] allows. The world is the one [worldOf] names
 * at call time (the cast being played). It talks to Bukkit directly because `IWorldGateway.playSound` has no category.
 */
public class PaperSkillSoundGateway(
    private val server: Server,
    private val worldOf: () -> WorldId,
) : ISkillSoundGateway {

    override fun play(sound: String, at: Vec3, category: String, volume: Double, pitch: Double) {
        val worldId = worldOf()
        val world = server.getWorld(worldId.value) ?: error("skill engine: world '${worldId.value}' of the cast is not loaded")
        val mixer = SoundCategory.entries.firstOrNull { it.name.equals(category, ignoreCase = true) }
            ?: error("skill engine: sound category '$category' is not one of ${SoundCategory.entries.map { it.name.lowercase() }}")
        val v = volume.coerceIn(0.0, SoundSpec.PRACTICAL_MAX_VOLUME).toFloat()
        val p = pitch.coerceIn(SoundSpec.MIN_PITCH, SoundSpec.MAX_PITCH).toFloat()
        world.playSound(Location(world, at.x, at.y, at.z), sound, mixer, v, p)
    }
}
