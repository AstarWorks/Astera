package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.status.StatusEffectId
import com.astarworks.astera.domain.model.status.StatusEffectInstance

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
     * to the cap.
     */
    public fun apply(instance: StatusEffectInstance)

    /** Remove a status effect immediately. */
    public fun remove(playerId: PlayerId, effectId: StatusEffectId)

    /** Clear all status effects on a player (death / match end). */
    public fun clear(playerId: PlayerId)
}
