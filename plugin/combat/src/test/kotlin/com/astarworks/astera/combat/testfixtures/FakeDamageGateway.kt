package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IDamageGateway] for use case tests.
 *
 * By default returns [DamageOutcome.HpReduced]. Override [nextResult] to inject
 * failure paths or [nextOutcome] to return a different success variant.
 * All calls are recorded in [applied] for assertion.
 */
public class FakeDamageGateway : IDamageGateway {

    public var nextOutcome: DamageOutcome = DamageOutcome.HpReduced(remainingHp = 10.0)

    /** When non-null, [apply] returns this instead of wrapping [nextOutcome]. */
    public var nextResult: Result<DamageOutcome, DamageApplicationError>? = null

    public val applied: MutableList<Triple<PlayerId, ResolvedDamage, DamageSource>> = mutableListOf()

    /** Parallel to [applied]: the `ignoreInvulnerability` flag of each call. */
    public val ignoreInvulnerabilityCalls: MutableList<Boolean> = mutableListOf()

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
        ignoreInvulnerability: Boolean,
    ): Result<DamageOutcome, DamageApplicationError> {
        ignoreInvulnerabilityCalls += ignoreInvulnerability
        applied += Triple(target, resolved, source)
        return nextResult ?: Result.success(nextOutcome)
    }
}
