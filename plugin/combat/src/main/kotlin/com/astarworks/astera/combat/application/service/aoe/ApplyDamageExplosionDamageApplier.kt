package com.astarworks.astera.combat.application.service.aoe

import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.aoe.ExplosionSpec
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.value.NumericValue
import org.slf4j.LoggerFactory

/**
 * Production binding for [ExplosionDamageApplier] (Wave 6e-N1, ADR-0164).
 *
 * Routes explosion entity damage through [ApplyDamageUseCase] so the
 * full Astera modifier pipeline applies — armor reduction (ADR-0058),
 * critical strike / headshot (Wave 5a), friendly-fire policy
 * (Wave 5a-fix2 ADR-0063), combo + focus-fire bonuses
 * (Wave 5combat-depth, ADR-0105). Without this binding, the Wave 6e
 * default `ExplosionDamageApplier.None` was an identity — explosions
 * applied no damage to entities, only the side-effecting block
 * destruction routed through [com.astarworks.astera.core.application.port.outbound.IBlockStateGateway].
 *
 * # Damage profile synthesis
 *
 * [ExplosionDamageApplier.apply] receives a pre-falloff'd [baseDamage]
 * computed by [com.astarworks.astera.combat.adapter.paper.aoe.PaperExplosionGateway]
 * (`baseDamage * (1 - distance/radius)`). Rebuilding the [DamageProfile]
 * with [baseDamage] preserves the explosion's attribute mix
 * (primary attribute from `spec.damageProfile.components.first()`) while
 * carrying the correct per-target falloff magnitude. Multi-component
 * profiles collapse to single-component on this path — Phase 6 may
 * iterate every component once the pipeline supports it.
 *
 * # Return value semantics
 *
 * The SPI return value populates `ExplosionResult.affectedPlayers` for
 * the published `ExplosionTriggered` event. We return [baseDamage] (the
 * pre-pipeline input) rather than attempting to extract the
 * post-pipeline number from [com.astarworks.astera.combat.application.port.outbound.DamageOutcome]
 * (which does not carry it). Downstream consumers that need the actually
 * applied damage subscribe to `DamageDealt` events, not
 * `ExplosionTriggered`.
 *
 * # Immunity / failure
 *
 * If [ApplyDamageUseCase] returns `Result.failure(...)` (target immune,
 * gateway error), the adapter logs WARN and returns `0.0` — the
 * `ExplosionResult.affectedPlayers` row reports zero damage to that
 * target, which is the truthful outcome.
 *
 * # Registration
 *
 * `AsteraCombatPlugin.onEnable` constructs this and passes it as the
 * `damageApplier` constructor arg of `PaperExplosionGateway` at
 * priority 100 (and to `InMemoryExplosionGateway` at priority 0 — same
 * binding flows through both impls).
 */
public class ApplyDamageExplosionDamageApplier(
    private val applyDamageUseCase: ApplyDamageUseCase,
) : ExplosionDamageApplier {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun apply(
        target: PlayerId,
        baseDamage: Double,
        spec: ExplosionSpec,
    ): Double {
        if (baseDamage <= 0.0) return 0.0
        val primaryComponent = spec.damageProfile.components.first()
        val profile = DamageProfile(
            components = listOf(
                DamageComponent(
                    base = NumericValue.Literal(baseDamage),
                    attribute = primaryComponent.attribute,
                    multiplier = primaryComponent.multiplier,
                ),
            ),
        )
        val attempt = DamageAttempt(
            targetId = target,
            baseProfile = profile,
            source = spec.source,
            headshot = false,
        )
        return when (val result = applyDamageUseCase.execute(attempt)) {
            is Result.Success -> baseDamage
            is Result.Failure -> {
                if (log.isDebugEnabled) {
                    log.debug(
                        "ApplyDamageExplosionDamageApplier: ApplyDamage failed for target={} baseDamage={} error={}",
                        target,
                        baseDamage,
                        result.error,
                    )
                }
                0.0
            }
        }
    }
}
