package com.astarworks.astera.combat.application.port.outbound.aoe

import com.astarworks.astera.combat.domain.aoe.AreaEffectError
import com.astarworks.astera.combat.domain.aoe.AreaEffectHandle
import com.astarworks.astera.combat.domain.aoe.AreaEffectSpec
import com.astarworks.astera.core.domain.Result

/**
 * Outbound port: spawn / despawn a persistent area-effect zone (Wave 5m,
 * ADR-0088).
 *
 * "Area effect" = a shape anchored at a position that periodically
 * applies an [com.astarworks.astera.combat.domain.aoe.AreaEffectAction]
 * to entities inside it, for a bounded duration. Used by:
 *  - **Poison cloud** (`ApplyStatusEffect(POISON)`) — Wave 5n boss skill.
 *  - **Fire ring** (`DealDamage(FIRE)`) — burning floor in arena.
 *  - **Smoke screen** (`SpawnParticle`) — purely visual.
 *
 * # Why a port (vs. ad-hoc schedulers in each skill handler):
 *  1. Centralised lifecycle — the gateway handles the despawn timer +
 *     domain events. Skill handlers would otherwise leak timers when a
 *     match ends early.
 *  2. Vendor-neutral. Wave 6a `PaperAreaEffectGateway` uses
 *     `World.spawnParticle` / `World.getNearbyEntities`; a future Folia
 *     impl can route to the region scheduler without changing callers.
 *  3. Analytics. [com.astarworks.astera.combat.domain.event.aoe.AreaEffectSpawned]
 *     /  `AreaEffectDespawned` give a clean stream of zone lifetimes
 *     without per-handler boilerplate.
 *
 * # Lifecycle
 *
 * - [spawn] returns an [AreaEffectHandle] + publishes
 *   `AreaEffectSpawned`.
 * - The gateway impl internally schedules the per-tick action and the
 *   final despawn at `durationTicks`.
 * - [despawn] (or the auto-fire at the end of duration) publishes
 *   `AreaEffectDespawned`. Idempotent — despawning an already-despawned
 *   handle returns [AreaEffectError.UnknownHandle].
 *
 * # Threading
 *
 * Called only from the Paper main thread.
 */
public interface IAreaEffectGateway {

    public fun spawn(spec: AreaEffectSpec): Result<AreaEffectHandle, AreaEffectError>

    public fun despawn(handle: AreaEffectHandle): Result<Unit, AreaEffectError>

    /** Snapshot of currently-active handles. O(n); admin / debug listing only. */
    public fun all(): Collection<AreaEffectHandle>
}
