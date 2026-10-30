package com.astarworks.astera.economy.platform.wiring

import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.LifecycleFailure
import com.astarworks.astera.core.platform.wiring.PluginLifecycle
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.stats.platform.wiring.StatsLifecycle

/**
 * Economy as a rewirable plugin (ADR-0311). [wire] is [EconomyWiring.install] (pure registration; the ledger, shop
 * stock, betting state and the other in-memory repositories are carried over via `WiringContext.stateful`);
 * [bind] starts the expiry sweeps and the match-shop bot buyer. Economy resolves Stats services, so a Stats rewire
 * rewires it too.
 */
public class EconomyLifecycle(private val ports: () -> EconomyPlatformPorts) : PluginLifecycle<EconomyWiringResult> {

    override val owner: WiringOwner = OWNER
    override val dependsOn: List<WiringOwner> = listOf(StatsLifecycle.OWNER)

    override fun wire(ctx: WiringContext): Result<EconomyWiringResult, LifecycleFailure> =
        try {
            Result.Success(EconomyWiring.install(ctx, ports()))
        } catch (e: Exception) {
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun bind(ctx: WiringContext, wired: EconomyWiringResult): Result<Unit, LifecycleFailure> =
        try {
            wired.bind()
            Result.Success(Unit)
        } catch (e: Exception) {
            wired.unbind()
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun unbind(ctx: WiringContext, wired: EconomyWiringResult) {
        wired.unbind()
    }

    public companion object {
        public val OWNER: WiringOwner = WiringOwner("Astera-Economy")
    }
}
