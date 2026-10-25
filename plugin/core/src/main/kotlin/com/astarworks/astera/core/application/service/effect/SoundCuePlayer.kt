package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [ISoundCuePlayer] (ADR-0256): cue id -> [com.astarworks.astera.core.domain.effect.sound.SoundDefinition]
 * (primary sound + layers) -> [IPlayerSoundGateway]. [gateway] is resolved per call so a Paper
 * overlay registered after this object was built is still used. [soundVolumePercent] is the
 * listener's `/astera volume sound` setting (0..100).
 */
public class SoundCuePlayer(
    private val registry: ISoundRegistry,
    private val gateway: () -> IPlayerSoundGateway,
    private val soundVolumePercent: (PlayerId) -> Int,
) : ISoundCuePlayer {

    private val log = LoggerFactory.getLogger(javaClass)
    private val warned: MutableSet<SoundId> = ConcurrentHashMap.newKeySet()

    override fun play(playerId: PlayerId, cue: SoundId, position: Vec3?, world: WorldId?): Boolean {
        val def = registry.find(cue)
        if (def == null) {
            if (warned.add(cue)) log.warn("Sound cue '{}' is not defined under content/sounds/; nothing played.", cue)
            return false
        }
        val percent = soundVolumePercent(playerId).coerceIn(0, 100)
        if (percent == 0) return false
        val scale = percent / 100.0
        val out = gateway()
        out.playForViewer(playerId, spec(def.resourceKey, def.defaultVolume * scale, def.defaultPitch), position, world)
        for (layer in def.layers) {
            out.playForViewer(playerId, spec(layer.resourceKey, layer.volume * scale, layer.pitch), position, world)
        }
        return true
    }

    override fun playAll(players: Iterable<PlayerId>, cue: SoundId, position: Vec3?, world: WorldId?) {
        for (p in players) play(p, cue, position, world)
    }

    private fun spec(key: String, volume: Double, pitch: Double): SoundSpec =
        SoundSpec(key = key, volume = volume.coerceIn(0.0, SoundSpec.PRACTICAL_MAX_VOLUME), pitch = pitch)
}
