package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.chain.EffectChain

/**
 * Outbound port: composite effect orchestration — timed sequences and
 * parallel branches of particle / sound / status / damage / delay steps
 * (Wave 5effects / ADR-0109).
 *
 * # Why this exists
 *
 * Boss phase transitions, combat finishers, match-event cinematics, and
 * tutorial-step "look here!" cues are all **compositions** of the
 * atomic effect primitives (particle / sound / status / damage). Writing
 * the composition by hand at each call site duplicates timing logic and
 * loses cancel atomicity (the boss despawns mid-cinematic → leftover
 * sound loops or rogue status applications). [play] returns a single
 * [EffectHandle] that, when cancelled, tears down every still-pending
 * step across every branch.
 *
 * # Cross-plugin bridges
 *
 * Status and damage steps live in Core's vocabulary (`StatusEffectId`,
 * `DamageProfile`) but the actual application happens in `:plugin:combat`.
 * The orchestrator looks up [IStatusEffectApplier] / [IDamageApplier] on
 * the registered side; combat registers concrete impls at `onEnable`.
 * Without combat the steps warn-log and continue.
 */
public interface IEffectOrchestrator {

    /**
     * Begin playback of [chain]. Returns an [EffectHandle] that controls
     * the entire composite — cancelling tears down every still-pending
     * step across every branch.
     *
     * If [chain] resolves to no executable work (e.g. an empty Sequence
     * defended by a YAML loader, a Loop with `durationTicks = 0` and no
     * steps), implementations return [EffectHandle.NONE].
     */
    public fun play(chain: EffectChain): EffectHandle
}
