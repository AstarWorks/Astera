package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.effect.IStatusEffectApplier
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * No-op [IStatusEffectApplier] (Wave 5effects / ADR-0109).
 *
 * Registered by `AsteraCorePlugin` at priority 0 so `InMemoryEffectOrchestrator`
 * can boot without combat on the classpath. Combat registers its real
 * `ApplyStatusUseCase`-backed adapter at priority 100.
 */
public class NoopStatusEffectApplier(
    private val log: Logger = LoggerFactory.getLogger(NoopStatusEffectApplier::class.java),
) : IStatusEffectApplier {

    override fun apply(target: PlayerId, effectId: StatusEffectId, durationTicks: Int) {
        log.debug(
            "NoopStatusEffectApplier.apply(target={}, effectId={}, ticks={}) — combat plugin not loaded",
            target, effectId, durationTicks,
        )
    }
}
