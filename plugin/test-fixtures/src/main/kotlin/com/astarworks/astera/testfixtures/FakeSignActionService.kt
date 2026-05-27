package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.sign.ISignActionService
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.sign.SignAction
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Test fake for [ISignActionService] (Wave 5block-embed, ADR-0120).
 *
 * Records every bind / unbind / lookup call so listener / use case tests
 * can assert against the call sequence without coupling to the production
 * impl's internal map layout.
 */
public class FakeSignActionService : ISignActionService {

    public data class BindLog(val world: WorldId, val position: Vec3, val action: SignAction)
    public data class UnbindLog(val world: WorldId, val position: Vec3)
    public data class LookupLog(val world: WorldId, val position: Vec3)

    public val bindLog: MutableList<BindLog> = mutableListOf()
    public val unbindLog: MutableList<UnbindLog> = mutableListOf()
    public val lookupLog: MutableList<LookupLog> = mutableListOf()

    private data class Slot(val world: WorldId, val position: Vec3)
    private val bindings: MutableMap<Slot, SignAction> = mutableMapOf()

    override fun bindSignAction(world: WorldId, position: Vec3, action: SignAction) {
        bindings[Slot(world, position)] = action
        bindLog += BindLog(world, position, action)
    }

    override fun unbindSign(world: WorldId, position: Vec3): Boolean {
        unbindLog += UnbindLog(world, position)
        return bindings.remove(Slot(world, position)) != null
    }

    override fun lookup(world: WorldId, position: Vec3): SignAction? {
        lookupLog += LookupLog(world, position)
        return bindings[Slot(world, position)]
    }
}
