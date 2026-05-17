package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Resolves and applies a [DamageAttempt] through the element matrix and
 * [IDamageGateway], publishing [DamageDealt] (and optionally [PlayerKilled])
 * on success.
 *
 * IDamageGateway implementation lands in Stage 7-改 (Paper adapter).
 * Until then this use case is wired only when the gateway is present in
 * ServiceRegistry.
 */
public class ApplyDamageUseCase(
    private val damageGateway: IDamageGateway,
    private val statusContainer: IStatusEffectContainer,
    private val elementMatrix: IElementMatrix,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(attempt: DamageAttempt): Result<DamageOutcome, ApplyDamageError> {
        // 1. Immunity check
        if (statusContainer.has(attempt.targetId, IMMUNITY_ID)) {
            return Result.failure(ApplyDamageError.TargetImmune(attempt.targetId))
        }

        // 2. Resolve raw damage via element matrix
        val resolved = DamageRule.resolve(attempt, elementMatrix = elementMatrix)

        // 3. Delegate to gateway (Paper adapter applies actual HP change)
        return when (val gatewayResult = damageGateway.apply(attempt.targetId, resolved, attempt.source)) {
            is Result.Success -> {
                val outcome = gatewayResult.value
                val attacker = attempt.source.attackerIdOrNull()

                // Always publish DamageDealt
                eventBus.publish(
                    DamageDealt(
                        attacker = attacker,
                        target = attempt.targetId,
                        resolved = resolved,
                        source = attempt.source,
                    ),
                )

                // Publish PlayerKilled when the gateway signals a kill
                if (outcome is DamageOutcome.Killed) {
                    eventBus.publish(
                        PlayerKilled(
                            victim = attempt.targetId,
                            killer = attacker,
                            source = attempt.source,
                            atTick = System.currentTimeMillis(), // Server tick not available here; Stage 7-改 can supply exact tick
                        ),
                    )
                }

                Result.success(outcome)
            }
            is Result.Failure -> Result.failure(ApplyDamageError.GatewayFailed(gatewayResult.error))
        }
    }

    private companion object {
        val IMMUNITY_ID = StatusEffectId("immunity")
    }
}

/** Extracts an attacker [PlayerId] from the source if one is present. */
private fun DamageSource.attackerIdOrNull(): PlayerId? = when (this) {
    is DamageSource.Weapon -> attackerId
    is DamageSource.Skill -> casterId
    is DamageSource.Environment, is DamageSource.Fall, is DamageSource.Explosion -> null
}

/** Expected failure modes of [ApplyDamageUseCase.execute]. */
public sealed class ApplyDamageError {
    public data class TargetImmune(val targetId: PlayerId) : ApplyDamageError()
    public data class GatewayFailed(val cause: DamageApplicationError) : ApplyDamageError()
}
