package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.LifecycleFailure
import com.astarworks.astera.core.platform.wiring.PluginLifecycle
import com.astarworks.astera.core.platform.wiring.WiringContext

/**
 * Stand-in for `PersistenceLifecycle` (ADR-0311 P3): same owner, same shape. [wire] registers a NEW generation of
 * overlay wrappers over the shared [SimPersistenceOverlay.SimPersistenceBacking] (like fresh Postgres repositories
 * over the same database), [reconnect] counts the call and can be made to fail.
 */
public class SimPersistenceLifecycle(
    private val backing: SimPersistenceOverlay.SimPersistenceBacking,
    private val onGeneration: (SimPersistenceOverlay) -> Unit,
) : PluginLifecycle<Unit> {

    override val owner: WiringOwner = OWNER
    override val dependsOn: List<WiringOwner> = emptyList()

    /** How many `--reconnect` runs reached this lifecycle. */
    public var reconnects: Int = 0
        private set

    /** Makes the next [reconnect] fail, like a database that is still down. */
    public var failNextReconnect: Boolean = false

    override fun wire(ctx: WiringContext): Result<Unit, LifecycleFailure> {
        onGeneration(backing.register(ctx.services))
        return Result.Success(Unit)
    }

    override fun bind(ctx: WiringContext, wired: Unit): Result<Unit, LifecycleFailure> = Result.Success(Unit)

    override fun unbind(ctx: WiringContext, wired: Unit) {}

    override fun reconnect(): Result<Unit, LifecycleFailure> {
        if (failNextReconnect) {
            failNextReconnect = false
            return Result.Failure(LifecycleFailure("stand-in database unreachable"))
        }
        reconnects++
        return Result.Success(Unit)
    }

    public companion object {
        public val OWNER: WiringOwner = WiringOwner("Astera-Persistence")
    }
}
