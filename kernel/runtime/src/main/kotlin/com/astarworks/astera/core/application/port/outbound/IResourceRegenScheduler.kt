package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: drives per-tick regen of Stamina / Mana / (rare-case Ammo)
 * resource pools (Wave 5k, ADR-0080).
 *
 * Wired to [IScheduler.repeat] at period = 1 tick. Each [tick] call:
 *
 *   1. Walks every initialised player's [com.astarworks.astera.core.domain.resource.PlayerResourcePool].
 *   2. For each [com.astarworks.astera.core.domain.resource.ResourceType]
 *      with `regenPerSecond > 0`, accumulates `regen / 20.0` per tick.
 *      Whole-integer overflow flushes into the pool via
 *      [IResourcePoolService.applyRegen]; fractional remainder carries
 *      forward.
 *
 * Returns the **set of players who saw a non-zero regen this tick** so the
 * HUD layer can target redraws (no full sweep needed every tick — a real
 * combat scenario only regens a handful of players at any given moment).
 *
 * # Why a port and not just an impl
 *
 * Splitting the scheduler out makes the regen rate / cadence testable
 * without spinning up a real [IScheduler]. The Phase 1 default impl is
 * straightforward (uniform 20 TPS), but Phase 6 may swap in a
 * pause-aware regen for paused matches or a per-region scheduler under
 * Folia.
 */
public interface IResourceRegenScheduler {

    /**
     * Advance regen by one tick at [currentTick]. Returns the set of
     * players whose pool changed (so HUD subscribers can re-render).
     *
     * Implementations decide when to flush sub-integer accumulator state;
     * the default flushes at every whole-integer crossing.
     */
    public fun tick(currentTick: Long): Set<PlayerId>
}
