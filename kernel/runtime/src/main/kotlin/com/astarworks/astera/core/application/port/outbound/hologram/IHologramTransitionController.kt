package com.astarworks.astera.core.application.port.outbound.hologram

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.hologram.transition.HologramTimeline
import com.astarworks.astera.core.domain.hologram.transition.HologramTransition
import com.astarworks.astera.core.domain.hologram.transition.HologramTransitionHandle
import kotlin.reflect.KClass

/**
 * Outbound port: schedule and cancel declarative tween animations on a
 * Display-entity-backed hologram (Wave 5transitions, ADR-0115 §E).
 *
 * Layers on top of [com.astarworks.astera.core.application.port.outbound.IHologramGateway]:
 * the gateway provides 16 per-type updaters (Wave 5transitions, ADR-0115 §A);
 * this controller drives them tick-by-tick using
 * [com.astarworks.astera.core.application.port.outbound.IScheduler] to step
 * smooth transitions and to enforce categorical-transition delays.
 *
 * # Two play overloads
 *
 * [play] for a single [HologramTransition] is the simple case — fire a
 * fade-in, swap an item, lerp a colour. The returned handle is for
 * cancellation only; the controller publishes
 * [com.astarworks.astera.core.domain.event.HologramTransitionStarted] /
 * [com.astarworks.astera.core.domain.event.HologramTransitionCompleted] /
 * [com.astarworks.astera.core.domain.event.HologramTransitionCancelled]
 * so subscribers don't have to poll.
 *
 * [play] for a [HologramTimeline] runs the timeline's transitions in
 * sequence and applies the [com.astarworks.astera.core.domain.hologram.transition.LoopMode]
 * to decide whether to wrap, ping-pong, or stop after the final
 * transition completes.
 *
 * # Validation timing
 *
 * Per-type transitions (e.g. `TextOpacity` against an `Item` hologram)
 * are validated **at play time**, not at construction — a transition
 * value is a pure data structure that doesn't know what hologram it
 * will run against. The controller resolves the hologram from [handle],
 * inspects its [HologramKind], and returns
 * [HologramTransitionError.TypeMismatch] before scheduling anything if
 * the kinds don't line up.
 *
 * # Cancellation semantics
 *
 * Cancellation is immediate from the caller's perspective: the handle is
 * revoked synchronously inside [cancel] / [cancelAll], and the next tick
 * dispatch skips revoked handles without touching the gateway. Already-
 * dispatched updaters on the cancelled tick may still apply — there is
 * no per-tick rollback (would require gateway snapshot history we
 * deliberately don't keep in the in-memory impl).
 *
 * # Disposal
 *
 * The controller's lifecycle is bound to its host plugin's `onDisable`.
 * Implementations MUST cancel all in-flight transitions when shutting
 * down, publishing
 * [com.astarworks.astera.core.domain.event.HologramTransitionCancelled]
 * with `reasonKind = "scheduler_shutdown"`. Tests use
 * `FakeHologramTransitionController.manualAdvance(...)` to step ticks
 * without a real scheduler.
 */
public interface IHologramTransitionController {

    /**
     * Schedule [transition] against the hologram identified by [handle].
     *
     * Validates kind compatibility (categorical / smooth per-type variants
     * require a matching [HologramKind]) before scheduling. On
     * incompatibility, returns
     * [HologramTransitionError.TypeMismatch] synchronously and publishes
     * no events.
     *
     * Returns a [HologramTransitionHandle] callers store for [cancel].
     * Multiple concurrent transitions on the same hologram are allowed —
     * each gets a distinct handle.
     */
    public fun play(
        handle: HologramHandle,
        transition: HologramTransition,
    ): Result<HologramTransitionHandle, HologramTransitionError>

    /**
     * Schedule the timeline's transitions in sequence, applying the
     * timeline's [com.astarworks.astera.core.domain.hologram.transition.LoopMode]
     * after the final transition completes.
     *
     * Returns a single handle for the **entire** timeline — cancelling
     * it stops every remaining transition. The timeline's transitions are
     * not individually addressable from outside.
     */
    public fun play(
        handle: HologramHandle,
        timeline: HologramTimeline,
    ): Result<HologramTransitionHandle, HologramTransitionError>

    /**
     * Cancel the transition / timeline identified by [transitionHandle].
     *
     * Idempotent: cancelling an unknown / already-cancelled handle is a
     * success (matches [IHologramGateway.despawn] convention). Publishes
     * [com.astarworks.astera.core.domain.event.HologramTransitionCancelled]
     * with `reasonKind = "manual"` for a live handle; no event for an
     * unknown handle.
     */
    public fun cancel(
        transitionHandle: HologramTransitionHandle,
    ): Result<Unit, HologramTransitionError>

    /**
     * Cancel every transition running on [handle].
     *
     * Used by `Hologram.despawn` flows + manual cleanup. Each cancelled
     * transition publishes one
     * [com.astarworks.astera.core.domain.event.HologramTransitionCancelled]
     * event with `reasonKind = "hologram_despawned"`.
     */
    public fun cancelAll(handle: HologramHandle): Result<Unit, HologramTransitionError>

    /**
     * Snapshot of every transition handle currently running against
     * [handle]. Returns empty if the hologram has none / is unknown.
     *
     * The returned collection is a defensive copy — mutating it does not
     * affect controller state.
     */
    public fun active(handle: HologramHandle): Collection<HologramTransitionHandle>
}

/**
 * Sealed error type for [IHologramTransitionController] operations
 * (Wave 5transitions, ADR-0115 §E).
 */
public sealed class HologramTransitionError {

    /** The hologram for [handle] does not exist on the underlying gateway. */
    public data class UnknownHologram(val handle: HologramHandle) : HologramTransitionError()

    /**
     * A per-type transition was scheduled against a hologram whose
     * current kind does not match. Carries [KClass] for log / GUI
     * rendering — same shape as
     * [com.astarworks.astera.core.application.port.outbound.HologramError.TypeMismatch].
     */
    public data class TypeMismatch(
        val expected: KClass<out HologramKind>,
        val actual: KClass<out HologramKind>,
    ) : HologramTransitionError()

    /** A second cancel call against a handle that already saw cancel. Idempotent treatment lifts this to Success. */
    public data class TransitionAlreadyCancelled(
        val transitionHandle: HologramTransitionHandle,
    ) : HologramTransitionError()

    /** The controller's scheduler was shut down before the transition could start. */
    public data object SchedulerUnavailable : HologramTransitionError()
}
