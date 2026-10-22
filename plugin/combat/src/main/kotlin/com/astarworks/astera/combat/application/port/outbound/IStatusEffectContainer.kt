package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-entity status effect container.
 *
 * One container instance per live entity (typically: per-player; Phase 5
 * extends to NPCs). Implementations track tick lifetime, stack count,
 * application / removal events. Persistence is per-impl: in-memory in Phase
 * 2 mid, Postgres-backed for Phase 4 long-lived match data.
 */
public interface IStatusEffectContainer {

    /** Currently-active effects on the given player. */
    public fun active(playerId: PlayerId): Collection<StatusEffectInstance>

    /** Is the given effect currently applied to the player? */
    public fun has(playerId: PlayerId, effectId: StatusEffectId): Boolean

    /**
     * Apply a status effect instance. Stacking behaviour follows
     * `StatusEffectSpec.stackable` / `maxStacks` on the underlying spec —
     * not-stackable effects refresh duration; stackable effects increment up
     * to the cap. A stackable [StatusEffectInstance.currentStacks] greater than one adds that many
     * stacks at once (`apply_status.stack_count`).
     */
    public fun apply(instance: StatusEffectInstance)

    /** Current stack count of [effectId] on [playerId]; `0` when the effect is not active. */
    public fun stacks(playerId: PlayerId, effectId: StatusEffectId): Int =
        active(playerId).firstOrNull { it.spec.id == effectId }?.currentStacks ?: 0

    /**
     * Consume [count] stacks of [effectId]; the effect is removed when no stack is left
     * (`remove_status.stacks`, `damage.per_stack.consume`). Containers without partial-stack
     * support fall back to removing the whole effect.
     */
    public fun removeStacks(playerId: PlayerId, effectId: StatusEffectId, count: Int) {
        remove(playerId, effectId)
    }

    /**
     * Apply many instances in one call (Wave 5g, ADR-0072).
     *
     * Default impl iterates [apply]; the Wave 6a `PaperStatusEffectAdapter`
     * may override for a batched packet flush. This is the only Wave 5g
     * delta to the port — Vanilla potion observation / overwrite logic
     * stays inside the Wave 6a Paper adapter (ADR-0002 / ADR-0030 §8: the
     * domain never imports `org.bukkit.potion.*`).
     */
    public fun applyBulk(instances: Collection<StatusEffectInstance>): Unit =
        instances.forEach { apply(it) }

    /** Remove a status effect immediately. */
    public fun remove(playerId: PlayerId, effectId: StatusEffectId)

    /** Clear all status effects on a player (death / match end). */
    public fun clear(playerId: PlayerId)
}
