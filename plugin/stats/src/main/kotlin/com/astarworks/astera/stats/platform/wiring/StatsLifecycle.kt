package com.astarworks.astera.stats.platform.wiring

import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.LifecycleFailure
import com.astarworks.astera.core.platform.wiring.PluginLifecycle
import com.astarworks.astera.core.platform.wiring.WiringContext

/**
 * Stats as a rewirable plugin (ADR-0311). [wire] is [StatsWiring.install] (pure registration, in-memory repositories
 * carried over via `WiringContext.stateful`); [bind] starts the season check and the lobby leaderboard board.
 */
public class StatsLifecycle(private val ports: () -> StatsPlatformPorts) : PluginLifecycle<StatsWiringResult> {

    override val owner: WiringOwner = OWNER
    override val dependsOn: List<WiringOwner> = emptyList()

    override fun wire(ctx: WiringContext): Result<StatsWiringResult, LifecycleFailure> =
        try {
            Result.Success(StatsWiring.install(ctx, ports()))
        } catch (e: Exception) {
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun bind(ctx: WiringContext, wired: StatsWiringResult): Result<Unit, LifecycleFailure> =
        try {
            wired.bind()
            Result.Success(Unit)
        } catch (e: Exception) {
            wired.unbind()
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun unbind(ctx: WiringContext, wired: StatsWiringResult) {
        wired.unbind()
    }

    public companion object {
        public val OWNER: WiringOwner = WiringOwner("Astera-Stats")
    }
}
