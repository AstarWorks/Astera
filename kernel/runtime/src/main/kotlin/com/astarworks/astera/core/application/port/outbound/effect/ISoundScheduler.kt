package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec

/**
 * Outbound port: tick-based scheduled sound emission engine
 * (Wave 5effects / ADR-0109).
 *
 * Sound-side counterpart to [IParticleScheduler]. Same lifecycle
 * convention: [schedule] returns an [EffectHandle] the caller is expected
 * to cancel on phase end / mob despawn / disconnect. Finite-duration
 * variants self-cancel.
 *
 * # Why a dedicated port (vs reusing `IWorldGateway.playSound`)
 *
 * `IWorldGateway` only fires single emissions. Charged-skill sustain
 * loops, multi-stage cast cues, and countdown tick stings all need
 * tick-scheduled patterns. Wave 6 Paper adapter wraps Bukkit's per-tick
 * `World.playSound(...)` once via this port; without the port every
 * caller would re-implement scheduling on top of `IScheduler`.
 */
public interface ISoundScheduler {
    public fun schedule(spec: ScheduledSoundSpec): EffectHandle
}
