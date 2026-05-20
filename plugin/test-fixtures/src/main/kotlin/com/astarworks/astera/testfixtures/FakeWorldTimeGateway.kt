package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway
import com.astarworks.astera.core.application.service.world.InMemoryWorldTimeGateway
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Cross-plugin Fake for [IWorldTimeGateway]. Wraps the in-memory impl and
 * exposes the recorded `setTime` / `freezeTime` calls for assertions.
 *
 * Wave 5n (ADR-0080).
 */
public class FakeWorldTimeGateway : IWorldTimeGateway {

    public data class SetTimeCall(val world: WorldId, val timeOfDay: Long)
    public data class FreezeCall(val world: WorldId, val frozen: Boolean)

    private val delegate: InMemoryWorldTimeGateway = InMemoryWorldTimeGateway()

    public val setTimeCalls: MutableList<SetTimeCall> = mutableListOf()
    public val freezeCalls: MutableList<FreezeCall> = mutableListOf()

    override fun setTime(world: WorldId, timeOfDay: Long) {
        setTimeCalls += SetTimeCall(world, timeOfDay)
        delegate.setTime(world, timeOfDay)
    }

    override fun freezeTime(world: WorldId, frozen: Boolean) {
        freezeCalls += FreezeCall(world, frozen)
        delegate.freezeTime(world, frozen)
    }

    override fun getCurrentTime(world: WorldId): Long? = delegate.getCurrentTime(world)

    override fun isTimeFrozen(world: WorldId): Boolean = delegate.isTimeFrozen(world)
}
