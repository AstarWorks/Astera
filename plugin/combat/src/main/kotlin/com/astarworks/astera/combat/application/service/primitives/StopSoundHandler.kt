package com.astarworks.astera.combat.application.service.primitives

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic

/** `stop_sound`: stops the sound [Mechanic.StopSound.soundId] for every player the targeter resolves (e.g. a charge-up rise cut before impact). */
internal class StopSoundHandler(private val deps: PrimitiveDeps) : PrimitiveHandler<Mechanic.StopSound> {
    override fun handle(mechanic: Mechanic.StopSound, ctx: MechanicContext) {
        val gateway = deps.playerSound()
        if (gateway == null) {
            deps.log.warn("stop_sound {} dropped: no IPlayerSoundGateway registered", mechanic.soundId)
            return
        }
        val key = deps.soundRegistry?.find(mechanic.soundId)?.resourceKey ?: mechanic.soundId.value
        for (player in deps.targeters.resolvePlayers(mechanic.targets, ctx)) {
            gateway.stop(player, key)
        }
    }
}
