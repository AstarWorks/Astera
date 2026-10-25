package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.effect.IDamageApplier
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * No-op [IDamageApplier] (Wave 5effects / ADR-0109).
 *
 * Sibling to [NoopStatusEffectApplier]; registered at priority 0 so the
 * orchestrator boots without combat. Combat registers an
 * `ApplyDamageUseCase`-backed adapter at priority 100.
 */
public class NoopDamageApplier(
    private val log: Logger = LoggerFactory.getLogger(NoopDamageApplier::class.java),
) : IDamageApplier {

    override fun apply(target: PlayerId, profile: DamageProfile) {
        log.debug(
            "NoopDamageApplier.apply(target={}, profile={}) — combat plugin not loaded",
            target, profile,
        )
    }
}
