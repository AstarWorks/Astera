package com.astarworks.astera.combat.platform.wiring

import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.LifecycleFailure
import com.astarworks.astera.core.platform.wiring.PluginLifecycle
import com.astarworks.astera.core.platform.wiring.WiringContext

/**
 * Combat as a rewirable plugin (ADR-0311 P2). [install] builds generation N+1 (host-neutral [CombatWiring.install]
 * plus whatever the host attaches); [bindHost] / [unbindHost] start and stop the host's own pieces (the Paper skill
 * engine, status tick, training dummies). Carried by `WiringContext.stateful`: active statuses, damage history,
 * combo / focus-fire / target-lock state, god mode, skins and the summon registry. Drained at unbind: in-flight
 * skill-engine casts, projectiles and puppets are cancelled visibly (they are entities of the old engine).
 */
public class CombatLifecycle(
    private val install: (WiringContext) -> CombatWiringResult,
    private val bindHost: (WiringContext, CombatWiringResult) -> Unit = { _, _ -> },
    private val unbindHost: (WiringContext) -> Unit = {},
) : PluginLifecycle<CombatWiringResult> {

    override val owner: WiringOwner = OWNER
    override val dependsOn: List<WiringOwner> = listOf(WiringOwner("Astera-Items"))

    override fun wire(ctx: WiringContext): Result<CombatWiringResult, LifecycleFailure> =
        try {
            Result.Success(install(ctx))
        } catch (e: Exception) {
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun bind(ctx: WiringContext, wired: CombatWiringResult): Result<Unit, LifecycleFailure> =
        try {
            wired.bind()
            bindHost(ctx, wired)
            Result.Success(Unit)
        } catch (e: Exception) {
            unbind(ctx, wired)
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun unbind(ctx: WiringContext, wired: CombatWiringResult) {
        unbindHost(ctx)
        wired.shutdown()
    }

    public companion object {
        public val OWNER: WiringOwner = WiringOwner("Astera-Combat")
    }
}
