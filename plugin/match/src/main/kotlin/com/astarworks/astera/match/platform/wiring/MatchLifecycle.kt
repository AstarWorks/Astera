package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.LifecycleFailure
import com.astarworks.astera.core.platform.wiring.PluginLifecycle
import com.astarworks.astera.core.platform.wiring.WiringContext

/**
 * Match as a rewirable plugin (ADR-0311 P2). [install] builds generation N+1 (host-neutral [MatchWiring.install]
 * plus whatever the host attaches); [bindHost] attaches the host's own listeners after the generation is active.
 *
 * Carried by `WiringContext.stateful`, so a running match keeps its phase, remaining time, scores and team members:
 * the match repository, mode keys, timer accumulators, spawn selector, generator / anomaly repositories, star and
 * Norma stores, world / replay repositories, matchmaking queue, ratings and the Annihilation pillar tracker. Rebuilt:
 * every use case, event handler, scheduler task and game-mode instance; [MatchWiringResult.bind] re-attaches each
 * running match to the new generation's mode instance (a mode's in-instance counters restart).
 */
public class MatchLifecycle(
    private val install: (WiringContext) -> MatchWiringResult,
    private val bindHost: (WiringContext, MatchWiringResult) -> Unit = { _, _ -> },
) : PluginLifecycle<MatchWiringResult> {

    override val owner: WiringOwner = OWNER
    override val dependsOn: List<WiringOwner> = listOf(WiringOwner("Astera-Combat"))

    override fun wire(ctx: WiringContext): Result<MatchWiringResult, LifecycleFailure> =
        try {
            Result.Success(install(ctx))
        } catch (e: Exception) {
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun bind(ctx: WiringContext, wired: MatchWiringResult): Result<Unit, LifecycleFailure> =
        try {
            bindHost(ctx, wired)
            wired.bind()
            Result.Success(Unit)
        } catch (e: Exception) {
            wired.shutdown()
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun unbind(ctx: WiringContext, wired: MatchWiringResult) {
        wired.shutdown()
    }

    public companion object {
        public val OWNER: WiringOwner = WiringOwner("Astera-Match")
    }
}
