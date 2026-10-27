package com.astarworks.astera.combat.application.service.primitives

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic

/** `telegraph` handler. Owned by the T5 territory; Phase A leaves a warn-and-drop stub. */
internal class TelegraphHandler(private val deps: PrimitiveDeps) : PrimitiveHandler<Mechanic.Telegraph> {
    override fun handle(mechanic: Mechanic.Telegraph, ctx: MechanicContext) {
        deps.notImplemented("telegraph")
    }
}
