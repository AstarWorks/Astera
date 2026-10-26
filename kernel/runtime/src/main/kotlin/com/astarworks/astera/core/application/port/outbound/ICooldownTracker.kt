package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: per-(player, key) cooldown tracking.
 *
 * Sealed [CooldownKey] so Weapon, Skill, and Status cooldowns can share
 * one in-memory store and one tick-scan loop.
 *
 * The implementation lives in `core/application/service/InMemoryCooldownTracker`.
 * Plugins that need their own granularity create a [CooldownKey.Weapon],
 * [CooldownKey.Skill], or [CooldownKey.Custom] — no cross-plugin imports needed.
 */
public interface ICooldownTracker {

    /** True iff the player still has time remaining on this key. */
    public fun isCoolingDown(playerId: PlayerId, key: CooldownKey): Boolean

    /** Remaining cooldown in ticks; 0 when not on cooldown. */
    public fun remainingTicks(playerId: PlayerId, key: CooldownKey): Long

    /** Start (or restart) a cooldown of [durationTicks] for the given key. */
    public fun start(playerId: PlayerId, key: CooldownKey, durationTicks: Long)

    /** Remove any active cooldown for (player, key). No-op if not present. */
    public fun reset(playerId: PlayerId, key: CooldownKey)

    /**
     * Scan all active entries at [currentTick] and remove those that have
     * expired. Returns the set of expired (player, key) pairs so callers
     * can publish events or perform follow-up logic.
     */
    public fun tick(currentTick: Long): Set<Pair<PlayerId, CooldownKey>>

    /** All active (non-expired) cooldown keys for [playerId]. */
    public fun activeKeysFor(playerId: PlayerId): Set<CooldownKey>
}
