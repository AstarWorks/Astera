package com.astarworks.astera.combat.application.usecase.status

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Applies a named status effect to a target player.
 *
 * Resolves the [com.astarworks.astera.combat.domain.status.StatusEffectSpec] from the
 * registry, constructs a [StatusEffectInstance] from the spec, delegates to the
 * container (which handles stacking / refresh per spec rules), and publishes
 * [StatusApplied].
 *
 * [durationTicks] overrides the spec's default if provided, allowing callers
 * (e.g. a skill mechanic) to supply a custom duration.
 */
public class ApplyStatusUseCase(
    private val effectRegistry: IStatusEffectRegistry,
    private val effectContainer: IStatusEffectContainer,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(
        targetId: PlayerId,
        effectId: StatusEffectId,
        durationTicks: Int? = null,
    ): Result<StatusEffectInstance, ApplyStatusError> {
        val spec = effectRegistry.find(effectId)
            ?: return Result.failure(ApplyStatusError.EffectNotFound(effectId))

        val ticks = durationTicks ?: DEFAULT_DURATION_TICKS
        val atTick = System.currentTimeMillis() / TICKS_TO_MS

        val instance = StatusEffectInstance(
            spec = spec,
            targetPlayerId = targetId,
            remainingTicks = ticks,
            currentStacks = 1,
            appliedAtTick = atTick,
        )

        effectContainer.apply(instance)
        eventBus.publish(StatusApplied(target = targetId, effect = instance, atTick = atTick))

        return Result.success(instance)
    }

    private companion object {
        const val DEFAULT_DURATION_TICKS: Int = 100 // 5 s at 20 tps
        const val TICKS_TO_MS: Long = 50L
    }
}

/** Expected failure modes of [ApplyStatusUseCase.execute]. */
public sealed class ApplyStatusError {
    public data class EffectNotFound(val effectId: StatusEffectId) : ApplyStatusError()
}
