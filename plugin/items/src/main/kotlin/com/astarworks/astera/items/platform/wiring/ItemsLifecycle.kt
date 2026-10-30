package com.astarworks.astera.items.platform.wiring

import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.LifecycleFailure
import com.astarworks.astera.core.platform.wiring.PluginLifecycle
import com.astarworks.astera.core.platform.wiring.WiringContext

/**
 * Items as a rewirable plugin (ADR-0311 P2). [install] builds generation N+1: the host-neutral
 * [ItemsWiring.install] plus whatever the host binds to it (the Paper entrypoint adds its click bridge and tooltip
 * guard through `ctx.listeners`). It registers only: tasks and subscriptions it asks for wait until [bind].
 * Running cooldowns, item instances and the refinement randomizer are carried by `WiringContext.stateful`.
 */
public class ItemsLifecycle(
    private val install: (WiringContext) -> ItemsWiringResult,
) : PluginLifecycle<ItemsWiringResult> {

    override val owner: WiringOwner = OWNER
    override val dependsOn: List<WiringOwner> = emptyList()

    override fun wire(ctx: WiringContext): Result<ItemsWiringResult, LifecycleFailure> =
        try {
            Result.Success(install(ctx))
        } catch (e: Exception) {
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun bind(ctx: WiringContext, wired: ItemsWiringResult): Result<Unit, LifecycleFailure> =
        Result.Success(Unit)

    override fun unbind(ctx: WiringContext, wired: ItemsWiringResult) {
        wired.shutdown()
    }

    public companion object {
        public val OWNER: WiringOwner = WiringOwner("Astera-Items")
    }
}
