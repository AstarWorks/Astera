package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec

/**
 * Outbound port: tick-based scheduled particle emission engine.
 *
 * Wave 5effects (ADR-0109). Closes the gap left by Wave 5f, which only
 * shipped single-emit primitives (`IWorldGateway.spawnParticle` +
 * `IParticleRegistry` catalog). Boss-phase transitions, AOE tells, skill
 * tells, and HUD cooldown rings all need tick-driven composite emission
 * patterns (loop / burst / pulse / ring-expansion); without this port
 * Wave 6 Paper adapters would have to invent the scheduling shape, which
 * defeats ADR-0022's "Paper layer is a pure executor" invariant.
 *
 * # Lifetime
 *
 * Every variant returns an [EffectHandle]; callers store the handle and
 * call [EffectHandle.cancel] on phase end, mob despawn, player disconnect,
 * or any other lifecycle boundary. The default in-memory implementation
 * also self-cancels when a finite-duration variant elapses.
 *
 * # Wiring
 *
 *  - In-memory default (`InMemoryParticleScheduler`) registered by
 *    `AsteraCorePlugin` at priority 0. Composes `IScheduler` + `IWorldGateway`.
 *  - Paper overlay (Wave 6) registers at priority 100 if a denser
 *    Adventure-side packet pipeline becomes preferable.
 *  - Tests resolve `FakeParticleScheduler` (in test-fixtures) which uses
 *    `FakeScheduler.advanceTicks(...)` for deterministic playback.
 */
public interface IParticleScheduler {
    /**
     * Schedule [spec]. Returns immediately with a handle; the actual
     * emission happens on the next dispatch tick (or per the spec's
     * `initialDelayTicks` / `atTick`).
     */
    public fun schedule(spec: ScheduledParticleSpec): EffectHandle
}
