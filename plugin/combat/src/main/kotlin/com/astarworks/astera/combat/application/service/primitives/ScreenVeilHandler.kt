package com.astarworks.astera.combat.application.service.primitives

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic

/** `screen_veil` handler. Owned by the T5 territory; Phase A leaves a warn-and-drop stub. */
internal class ScreenVeilHandler(private val deps: PrimitiveDeps) : PrimitiveHandler<Mechanic.ScreenVeil> {
    override fun handle(mechanic: Mechanic.ScreenVeil, ctx: MechanicContext) {
        deps.notImplemented("screen_veil")
    }
}
