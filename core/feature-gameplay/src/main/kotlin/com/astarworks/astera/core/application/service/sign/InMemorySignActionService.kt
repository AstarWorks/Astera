package com.astarworks.astera.core.application.service.sign

import com.astarworks.astera.core.application.port.outbound.sign.ISignActionService
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.sign.SignAction
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Default in-memory [ISignActionService] (Wave 5block-embed, ADR-0120).
 *
 * One binding per `(world, position)` slot; rebind overwrites. Lives in
 * `core.application.service.sign.*` per the layer convention; wired by
 * `AsteraCorePlugin` at priority 0.
 */
public class InMemorySignActionService : ISignActionService {

    private data class Slot(val world: WorldId, val position: Vec3)

    private val bindings: MutableMap<Slot, SignAction> = mutableMapOf()

    override fun bindSignAction(world: WorldId, position: Vec3, action: SignAction) {
        bindings[Slot(world, position)] = action
    }

    override fun unbindSign(world: WorldId, position: Vec3): Boolean =
        bindings.remove(Slot(world, position)) != null

    override fun lookup(world: WorldId, position: Vec3): SignAction? =
        bindings[Slot(world, position)]

    /** Test helper: total binding count. */
    public val size: Int get() = bindings.size
}
