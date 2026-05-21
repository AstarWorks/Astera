package com.astarworks.astera.combat.testfixtures.aoe

import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.domain.aoe.ExplosionError
import com.astarworks.astera.combat.domain.aoe.ExplosionResult
import com.astarworks.astera.combat.domain.aoe.ExplosionSpec
import com.astarworks.astera.core.domain.Result

/**
 * In-memory recording fake for [IExplosionGateway] (Wave 5m, ADR-0088).
 *
 * Records every [trigger] invocation so use-case tests can assert on
 * call order + the spec that was passed in. Returns a configurable
 * canned [ExplosionResult] (default: empty).
 */
public class FakeExplosionGateway(
    private var canned: Result<ExplosionResult, ExplosionError> = Result.success(
        ExplosionResult(affectedPlayers = emptyList(), affectedBlocks = 0),
    ),
) : IExplosionGateway {

    /** Every spec passed to [trigger] in invocation order. */
    public val triggered: MutableList<ExplosionSpec> = mutableListOf()

    /** Force the next-and-all subsequent [trigger] calls to return [outcome]. */
    public fun stub(outcome: Result<ExplosionResult, ExplosionError>) {
        canned = outcome
    }

    override fun trigger(spec: ExplosionSpec): Result<ExplosionResult, ExplosionError> {
        triggered += spec
        return canned
    }

    /** Test-only: drop all recorded state and reset the canned result. */
    public fun clear() {
        triggered.clear()
        canned = Result.success(ExplosionResult(emptyList(), 0))
    }
}
