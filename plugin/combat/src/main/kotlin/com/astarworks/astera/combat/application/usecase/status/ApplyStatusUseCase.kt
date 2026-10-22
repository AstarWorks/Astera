package com.astarworks.astera.combat.application.usecase.status

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Applies a named status effect to a target player.
 *
 * Resolves the [com.astarworks.astera.combat.domain.status.StatusEffectSpec] from the
 * registry, constructs a [StatusEffectInstance] from the spec, delegates to the
 * container (which handles stacking / refresh per spec rules). The container
 * publishes [com.astarworks.astera.combat.domain.event.StatusApplied] (with the
 * merged stack count); this use case must not publish it a second time.
 *
 * [durationTicks] overrides the spec's default if provided, allowing callers
 * (e.g. a skill mechanic) to supply a custom duration. [stacks] is how many
 * stacks this application adds (the container caps at the spec's `maxStacks`).
 */
public class ApplyStatusUseCase(
    private val effectRegistry: IStatusEffectRegistry,
    private val effectContainer: IStatusEffectContainer,
    private val scheduler: IScheduler,
) {
    public fun execute(
        targetId: PlayerId,
        effectId: StatusEffectId,
        durationTicks: Int? = null,
        sourceId: PlayerId? = null,
        stacks: Int = 1,
    ): Result<StatusEffectInstance, ApplyStatusError> {
        val spec = effectRegistry.find(effectId)
            ?: return Result.failure(ApplyStatusError.EffectNotFound(effectId))
        return Result.success(applySpec(targetId, spec, durationTicks, sourceId, stacks))
    }

    /**
     * Applies an already-resolved [spec] (mechanic-built statuses such as
     * Freeze / Silence that have no `content/status/` entry).
     */
    public fun applySpec(
        targetId: PlayerId,
        spec: StatusEffectSpec,
        durationTicks: Int? = null,
        sourceId: PlayerId? = null,
        stacks: Int = 1,
    ): StatusEffectInstance {
        val instance = StatusEffectInstance(
            spec = spec,
            targetPlayerId = targetId,
            remainingTicks = durationTicks ?: DEFAULT_DURATION_TICKS,
            currentStacks = stacks.coerceAtLeast(1),
            appliedAtTick = scheduler.currentTick(),
            sourcePlayerId = sourceId,
        )
        effectContainer.apply(instance)
        return instance
    }

    private companion object {
        const val DEFAULT_DURATION_TICKS: Int = 100 // 5 s at 20 tps
    }
}

/** Expected failure modes of [ApplyStatusUseCase.execute]. */
public sealed class ApplyStatusError {
    public data class EffectNotFound(val effectId: StatusEffectId) : ApplyStatusError()
}
