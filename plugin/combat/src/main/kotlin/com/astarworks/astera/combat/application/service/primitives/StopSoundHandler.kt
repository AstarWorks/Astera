package com.astarworks.astera.combat.application.service.primitives

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic

/** `stop_sound` handler. Owned by the T1 sound territory; Phase A leaves a warn-and-drop stub. */
internal class StopSoundHandler(private val deps: PrimitiveDeps) : PrimitiveHandler<Mechanic.StopSound> {
    override fun handle(mechanic: Mechanic.StopSound, ctx: MechanicContext) {
        deps.notImplemented("stop_sound")
    }
}
