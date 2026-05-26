package com.astarworks.astera.core.domain.effect.chain

/**
 * Composition of [EffectStep]s — declarative description of a "compound
 * effect" (boss phase transition cinematic, combat finisher chain, match
 * countdown sting). Input to `IEffectOrchestrator.play(...)`.
 *
 * Wave 5effects (ADR-0109).
 *
 * # Variants
 *
 *  - [EmitOnce] — fire a single [EffectStep] immediately (or after
 *    `DelayStep` if the step is one).
 *  - [Sequence] — fire [steps] in declaration order. Each step runs to
 *    completion before the next starts. `DelayStep` is the canonical way
 *    to insert a gap.
 *  - [Parallel] — fire every branch concurrently. The chain is "active"
 *    while any branch is still running; cancelling the handle cancels
 *    every branch.
 *  - [Loop] — repeat [chain] until [durationTicks] elapses (or the handle
 *    is cancelled). `durationTicks == 0` means "loop forever".
 *
 * # Validation
 *
 * Empty `Sequence.steps` or empty `Parallel.branches` are construction
 * errors. The orchestrator's `play(...)` returns
 * [com.astarworks.astera.core.domain.effect.EffectHandle.NONE] when handed
 * a chain that resolves to no work after pre-flight checks, but the data
 * type itself rejects clearly-empty input so authoring typos surface at
 * the YAML loader layer.
 */
public sealed interface EffectChain {

    public data class EmitOnce(public val step: EffectStep) : EffectChain

    public data class Sequence(public val steps: List<EffectStep>) : EffectChain {
        init {
            require(steps.isNotEmpty()) { "Sequence steps must not be empty" }
        }
    }

    public data class Parallel(public val branches: List<EffectChain>) : EffectChain {
        init {
            require(branches.isNotEmpty()) { "Parallel branches must not be empty" }
        }
    }

    public data class Loop(
        public val chain: EffectChain,
        public val durationTicks: Long,
        public val intervalTicks: Long = 1L,
    ) : EffectChain {
        init {
            require(durationTicks >= 0) { "durationTicks must be >= 0, got $durationTicks" }
            require(intervalTicks >= 1) { "intervalTicks must be >= 1, got $intervalTicks" }
        }
    }
}
