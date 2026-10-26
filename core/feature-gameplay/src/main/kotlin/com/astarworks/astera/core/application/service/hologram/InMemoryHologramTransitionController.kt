package com.astarworks.astera.core.application.service.hologram

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IHologramGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.hologram.HologramTransitionError
import com.astarworks.astera.core.application.port.outbound.hologram.IHologramTransitionController
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.HologramTransitionCancelled
import com.astarworks.astera.core.domain.event.HologramTransitionCompleted
import com.astarworks.astera.core.domain.event.HologramTransitionStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.Hologram
import com.astarworks.astera.core.domain.hologram.HologramBrightness
import com.astarworks.astera.core.domain.hologram.HologramColor
import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.hologram.HologramTransform
import com.astarworks.astera.core.domain.hologram.transition.HologramColorInterpolator
import com.astarworks.astera.core.domain.hologram.transition.HologramRotationInterpolator
import com.astarworks.astera.core.domain.hologram.transition.HologramTimeline
import com.astarworks.astera.core.domain.hologram.transition.HologramTransition
import com.astarworks.astera.core.domain.hologram.transition.HologramTransitionHandle
import com.astarworks.astera.core.domain.hologram.transition.LoopMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Default in-memory [IHologramTransitionController] (Wave 5transitions,
 * ADR-0115 §F).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6 / a future Paper
 * adapter may register a higher-priority impl that drives Bukkit
 * `Display.interpolationDuration` directly (vanilla interpolation) rather
 * than per-tick gateway updates. Until then, the in-memory controller
 * runs a single repeating scheduler task that steps every active
 * transition once per tick.
 *
 * # Why one repeating task vs. per-transition tasks
 *
 * `IScheduler.repeatTicks(period = 1)` once is cheaper than
 * registering N scheduler tasks (N transitions concurrent). The tick loop
 * iterates the live transition set, computes each transition's progress
 * from `(currentTick - startTick) / durationTicks`, and writes the
 * tween's instant value through the gateway. Cancelled handles are
 * removed lazily on the next tick (so the cancel call itself is O(1)).
 *
 * # Composite + Timeline mechanics
 *
 * - **Composite** opens N child entries at the same `startTick`; the
 *   composite parent completes when every child reaches its
 *   `endTick`. Child entries publish no separate events — only the
 *   parent does. Cancel of the parent cascades to every child.
 * - **Timeline** opens one child entry per timeline transition; only
 *   one child is "active" at a time. When the active child completes,
 *   the controller advances to the next. After the last transition,
 *   [LoopMode] decides: `PlayOnce` → publish Completed; `Loop` → reset
 *   to first transition with `times--`; `PingPong` → reverse the
 *   sequence (with `times` counting cycles of forward+back).
 *
 * # Test usage
 *
 * Tests inject a controllable `IScheduler` (e.g. a fake that records
 * scheduled callbacks and lets the test advance ticks manually). The
 * controller has no direct test-helper API — to step a tween, advance
 * the scheduler. See `FakeHologramTransitionController` for a
 * record-and-replay alternative that does not need a scheduler.
 *
 * # Disposal
 *
 * Call [shutdown] from `onDisable` to cancel the repeating task and
 * publish `HologramTransitionCancelled(reasonKind = "scheduler_shutdown")`
 * for every live transition. Idempotent.
 */
public class InMemoryHologramTransitionController(
    private val gateway: IHologramGateway,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { scheduler.currentTick() },
) : IHologramTransitionController {

    // Per-transition runtime state. Holding the full plan (transition or
    // timeline) lets cancellation and timeline-step both find the next
    // action without a separate lookup table.
    private sealed interface ActiveEntry {
        val handle: HologramHandle
        val transitionHandle: HologramTransitionHandle
        val startTick: Long
    }

    private data class SingleEntry(
        override val handle: HologramHandle,
        override val transitionHandle: HologramTransitionHandle,
        override val startTick: Long,
        val transition: HologramTransition,
        // For Smooth / Categorical: the start-state snapshot at the moment of play,
        // captured so the controller can lerp from-state → target without re-reading
        // the live hologram every tick (which would drift if a parallel update fired).
        val fromState: TweenStartState?,
    ) : ActiveEntry

    private data class CompositeEntry(
        override val handle: HologramHandle,
        override val transitionHandle: HologramTransitionHandle,
        override val startTick: Long,
        val children: List<SingleEntry>,
        val pendingChildren: MutableSet<HologramTransitionHandle>,
    ) : ActiveEntry

    private data class TimelineEntry(
        override val handle: HologramHandle,
        override val transitionHandle: HologramTransitionHandle,
        override val startTick: Long,
        val timeline: HologramTimeline,
        var currentIndex: Int,
        var currentChildStartTick: Long,
        var currentChild: SingleEntry,
        // PingPong needs to know whether we're traversing forward (true) or backward (false).
        var traversingForward: Boolean,
        // For finite loops, decrement on each full cycle. null = infinite.
        var remainingCycles: Int?,
    ) : ActiveEntry

    /**
     * Captured pre-tween state for a single Smooth/Categorical entry.
     * Holds only the fields the active transition cares about — captured
     * at [play] time so subsequent updates don't drift the start-state.
     */
    private data class TweenStartState(
        val transform: HologramTransform?,
        val brightness: HologramBrightness?,
        val glowColor: HologramColor?,
        val shadowRadius: Float?,
        val shadowStrength: Float?,
        val viewRange: Float?,
        val textOpacity: Byte?,
        val textBackgroundColor: HologramColor?,
        val textLineWidth: Int?,
    )

    private val active: ConcurrentHashMap<HologramTransitionHandle, ActiveEntry> = ConcurrentHashMap()
    // index by hologram → set of transition handles, for cancelAll + active()
    private val byHologram: ConcurrentHashMap<HologramHandle, MutableSet<HologramTransitionHandle>> =
        ConcurrentHashMap()
    @Volatile
    private var schedulerTask: IScheduler.TaskHandle? = null
    @Volatile
    private var shutdown: Boolean = false

    init {
        // Single repeating tick driver. Idempotent on shutdown.
        schedulerTask = scheduler.runRepeatingTicks(ExecutionScope.Global, initialDelayTicks = 1L, periodTicks = 1L) {
            step()
        }
    }

    override fun play(
        handle: HologramHandle,
        transition: HologramTransition,
    ): Result<HologramTransitionHandle, HologramTransitionError> {
        if (shutdown) return Result.failure(HologramTransitionError.SchedulerUnavailable)
        val hologram = lookupHologram(handle)
            ?: return Result.failure(HologramTransitionError.UnknownHologram(handle))
        val validationFailure = validateKindCompatibility(hologram, transition)
        if (validationFailure != null) return Result.failure(validationFailure)
        val parentHandle = mintHandle()
        val now = tickClock()
        val entry = buildEntry(handle, parentHandle, transition, hologram, startTick = now)
        register(parentHandle, entry)
        eventBus.publish(
            HologramTransitionStarted(
                handle = handle,
                transitionHandle = parentHandle,
                atTick = now,
            ),
        )
        return Result.success(parentHandle)
    }

    override fun play(
        handle: HologramHandle,
        timeline: HologramTimeline,
    ): Result<HologramTransitionHandle, HologramTransitionError> {
        if (shutdown) return Result.failure(HologramTransitionError.SchedulerUnavailable)
        val hologram = lookupHologram(handle)
            ?: return Result.failure(HologramTransitionError.UnknownHologram(handle))
        // Validate every step's kind up-front so a 5-step timeline whose
        // step 4 mis-types fails at play-time, not 4 ticks in.
        for (step in timeline.transitions) {
            val failure = validateKindCompatibility(hologram, step)
            if (failure != null) return Result.failure(failure)
        }
        val parentHandle = mintHandle()
        val now = tickClock()
        val firstChild = buildSingleEntry(
            handle = handle,
            transitionHandle = mintHandle(),
            transition = timeline.transitions.first(),
            hologram = hologram,
            startTick = now,
        )
        // `remainingCycles` is the number of *additional* cycles after the
        // initial one. `times = 1` means "play once total" = 0 additional;
        // `times = 3` means "play 3 cycles total" = 2 additional. `null` =
        // infinite — sentinel value of -1 below is unused (we branch on null
        // explicitly in advanceTimeline).
        val initialRemainingCycles = when (val mode = timeline.loopMode) {
            LoopMode.PlayOnce -> 0
            is LoopMode.Loop -> mode.times?.let { it - 1 }
            is LoopMode.PingPong -> mode.times?.let { it - 1 }
        }
        val entry = TimelineEntry(
            handle = handle,
            transitionHandle = parentHandle,
            startTick = now,
            timeline = timeline,
            currentIndex = 0,
            currentChildStartTick = now,
            currentChild = firstChild,
            traversingForward = true,
            remainingCycles = initialRemainingCycles,
        )
        register(parentHandle, entry)
        eventBus.publish(
            HologramTransitionStarted(
                handle = handle,
                transitionHandle = parentHandle,
                atTick = now,
            ),
        )
        return Result.success(parentHandle)
    }

    override fun cancel(
        transitionHandle: HologramTransitionHandle,
    ): Result<Unit, HologramTransitionError> {
        val removed = active.remove(transitionHandle) ?: return Result.success(Unit)
        unregisterFromHologramIndex(removed.handle, transitionHandle)
        eventBus.publish(
            HologramTransitionCancelled(
                handle = removed.handle,
                transitionHandle = transitionHandle,
                reasonKind = REASON_MANUAL,
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun cancelAll(handle: HologramHandle): Result<Unit, HologramTransitionError> {
        val handles = byHologram.remove(handle) ?: return Result.success(Unit)
        for (transitionHandle in handles.toList()) {
            val removed = active.remove(transitionHandle) ?: continue
            eventBus.publish(
                HologramTransitionCancelled(
                    handle = removed.handle,
                    transitionHandle = transitionHandle,
                    reasonKind = REASON_HOLOGRAM_DESPAWNED,
                    atTick = tickClock(),
                ),
            )
        }
        return Result.success(Unit)
    }

    override fun active(handle: HologramHandle): Collection<HologramTransitionHandle> {
        return byHologram[handle]?.toList() ?: emptyList()
    }

    /**
     * Cancel every in-flight transition and stop the tick driver. Publishes
     * [HologramTransitionCancelled] with `reasonKind = "scheduler_shutdown"`
     * for each. Idempotent.
     */
    public fun shutdown() {
        if (shutdown) return
        shutdown = true
        schedulerTask?.cancel()
        schedulerTask = null
        val tick = tickClock()
        val handles = active.keys.toList()
        for (transitionHandle in handles) {
            val removed = active.remove(transitionHandle) ?: continue
            unregisterFromHologramIndex(removed.handle, transitionHandle)
            eventBus.publish(
                HologramTransitionCancelled(
                    handle = removed.handle,
                    transitionHandle = transitionHandle,
                    reasonKind = REASON_SCHEDULER_SHUTDOWN,
                    atTick = tick,
                ),
            )
        }
    }

    // ─── Tick step ──────────────────────────────────────────────────────

    private fun step() {
        if (shutdown) return
        val tick = tickClock()
        // Snapshot keys so we can mutate `active` during iteration.
        for (transitionHandle in active.keys.toList()) {
            val entry = active[transitionHandle] ?: continue
            val hologram = lookupHologram(entry.handle) ?: continue
            scheduler.run(regionScope(hologram.worldId, hologram.position), kotlin.time.Duration.ZERO) {
                if (active[transitionHandle] !== entry) return@run
                when (entry) {
                    is SingleEntry -> stepSingle(entry, tick)
                    is CompositeEntry -> stepComposite(entry, tick)
                    is TimelineEntry -> stepTimeline(entry, tick)
                }
            }
        }
    }

    private fun stepSingle(entry: SingleEntry, tick: Long) {
        val completed = applyTransitionStep(
            handle = entry.handle,
            transition = entry.transition,
            fromState = entry.fromState,
            startTick = entry.startTick,
            tick = tick,
        )
        if (completed) {
            completeEntry(entry, tick)
        }
    }

    private fun stepComposite(entry: CompositeEntry, tick: Long) {
        // Iterate over a snapshot; remove from pendingChildren as they finish.
        for (child in entry.children) {
            if (child.transitionHandle !in entry.pendingChildren) continue
            val completed = applyTransitionStep(
                handle = child.handle,
                transition = child.transition,
                fromState = child.fromState,
                startTick = child.startTick,
                tick = tick,
            )
            if (completed) entry.pendingChildren.remove(child.transitionHandle)
        }
        if (entry.pendingChildren.isEmpty()) {
            completeEntry(entry, tick)
        }
    }

    private fun stepTimeline(entry: TimelineEntry, tick: Long) {
        val completed = applyTransitionStep(
            handle = entry.handle,
            transition = entry.currentChild.transition,
            fromState = entry.currentChild.fromState,
            startTick = entry.currentChildStartTick,
            tick = tick,
        )
        if (!completed) return
        // Current child done — advance per LoopMode + traversal direction.
        advanceTimeline(entry, tick)
    }

    private fun advanceTimeline(entry: TimelineEntry, tick: Long) {
        val size = entry.timeline.transitions.size
        val nextIndex = if (entry.traversingForward) entry.currentIndex + 1 else entry.currentIndex - 1
        if (nextIndex in 0 until size) {
            startTimelineChild(entry, nextIndex, tick)
            return
        }
        // Wrap or stop.
        when (val mode = entry.timeline.loopMode) {
            LoopMode.PlayOnce -> {
                completeEntry(entry, tick)
            }
            is LoopMode.Loop -> {
                val remaining = entry.remainingCycles
                if (remaining != null && remaining <= 0) {
                    completeEntry(entry, tick); return
                }
                if (remaining != null) entry.remainingCycles = remaining - 1
                startTimelineChild(entry, 0, tick)
            }
            is LoopMode.PingPong -> {
                // We finished traversal in one direction. Flip + advance.
                if (entry.traversingForward) {
                    // forward done — flip to backward, start at size-1 (re-play last)
                    entry.traversingForward = false
                    startTimelineChild(entry, size - 1, tick)
                } else {
                    // backward done — one full cycle complete. Decrement + flip.
                    val remaining = entry.remainingCycles
                    if (remaining != null && remaining <= 0) {
                        completeEntry(entry, tick); return
                    }
                    if (remaining != null) entry.remainingCycles = remaining - 1
                    entry.traversingForward = true
                    startTimelineChild(entry, 0, tick)
                }
            }
        }
    }

    private fun startTimelineChild(entry: TimelineEntry, index: Int, tick: Long) {
        val hologram = lookupHologram(entry.handle) ?: run {
            // Hologram vanished between ticks — cancel + bail.
            cancelDueToVanishedHologram(entry, tick); return
        }
        val transition = entry.timeline.transitions[index]
        val child = buildSingleEntry(
            handle = entry.handle,
            transitionHandle = mintHandle(),
            transition = transition,
            hologram = hologram,
            startTick = tick,
        )
        entry.currentIndex = index
        entry.currentChildStartTick = tick
        entry.currentChild = child
    }

    private fun cancelDueToVanishedHologram(entry: ActiveEntry, tick: Long) {
        active.remove(entry.transitionHandle)
        unregisterFromHologramIndex(entry.handle, entry.transitionHandle)
        eventBus.publish(
            HologramTransitionCancelled(
                handle = entry.handle,
                transitionHandle = entry.transitionHandle,
                reasonKind = REASON_HOLOGRAM_DESPAWNED,
                atTick = tick,
            ),
        )
    }

    private fun completeEntry(entry: ActiveEntry, tick: Long) {
        active.remove(entry.transitionHandle)
        unregisterFromHologramIndex(entry.handle, entry.transitionHandle)
        eventBus.publish(
            HologramTransitionCompleted(
                handle = entry.handle,
                transitionHandle = entry.transitionHandle,
                atTick = tick,
            ),
        )
    }

    // ─── Apply one tick of a single transition ──────────────────────────

    /**
     * Apply one tick of [transition]'s effect to [handle] via the gateway.
     * Returns true iff the transition has finished its run (smooth: tick
     * reached endTick; categorical: delay elapsed and final set fired).
     *
     * Composite is **never** passed here directly — the composite entry
     * iterates its [SingleEntry] children which each carry a non-Composite
     * transition. Passing a Composite would be a programming error
     * (return false to avoid infinite loop; controller does not currently
     * support nested composites by design).
     */
    private fun applyTransitionStep(
        handle: HologramHandle,
        transition: HologramTransition,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        return when (transition) {
            is HologramTransition.Transform -> stepTransform(handle, transition, fromState, startTick, tick)
            is HologramTransition.Brightness -> stepBrightness(handle, transition, fromState, startTick, tick)
            is HologramTransition.GlowColor -> stepGlowColor(handle, transition, fromState, startTick, tick)
            is HologramTransition.ShadowRadius -> stepShadowRadius(handle, transition, fromState, startTick, tick)
            is HologramTransition.ShadowStrength -> stepShadowStrength(handle, transition, fromState, startTick, tick)
            is HologramTransition.ViewRange -> stepViewRange(handle, transition, fromState, startTick, tick)
            is HologramTransition.TextOpacity -> stepTextOpacity(handle, transition, fromState, startTick, tick)
            is HologramTransition.TextBackgroundColor -> stepTextBackgroundColor(handle, transition, fromState, startTick, tick)
            is HologramTransition.TextLineWidth -> stepTextLineWidth(handle, transition, fromState, startTick, tick)
            is HologramTransition.TextContent -> stepCategoricalAfter(transition.delayTicks, startTick, tick) {
                gateway.updateTextContent(handle, transition.targetLines)
            }
            is HologramTransition.TextAlignmentSet -> stepCategoricalAfter(transition.delayTicks, startTick, tick) {
                gateway.updateTextAlignment(handle, transition.targetAlignment)
            }
            is HologramTransition.TextSeeThrough -> stepCategoricalAfter(transition.delayTicks, startTick, tick) {
                gateway.updateTextSeeThrough(handle, transition.targetSeeThrough)
            }
            is HologramTransition.TextShadowed -> stepCategoricalAfter(transition.delayTicks, startTick, tick) {
                gateway.updateTextShadowed(handle, transition.targetShadowed)
            }
            is HologramTransition.TextDefaultBackground -> stepCategoricalAfter(transition.delayTicks, startTick, tick) {
                gateway.updateTextDefaultBackground(handle, transition.target)
            }
            is HologramTransition.ItemSwap -> stepCategoricalAfter(transition.delayTicks, startTick, tick) {
                gateway.updateItemId(handle, transition.targetItemId)
            }
            is HologramTransition.ItemDisplayModeSet -> stepCategoricalAfter(transition.delayTicks, startTick, tick) {
                gateway.updateItemDisplayMode(handle, transition.targetMode)
            }
            is HologramTransition.BlockData -> stepCategoricalAfter(transition.delayTicks, startTick, tick) {
                gateway.updateBlockData(handle, transition.targetBlockKey)
            }
            is HologramTransition.Billboard -> stepCategoricalAfter(transition.delayTicks, startTick, tick) {
                gateway.updateBillboard(handle, transition.targetBillboard)
            }
            is HologramTransition.Composite -> false // Composite is dispatched at parent level, not here.
        }
    }

    private inline fun stepCategoricalAfter(
        delayTicks: Int,
        startTick: Long,
        tick: Long,
        crossinline apply: () -> Unit,
    ): Boolean {
        val elapsed = tick - startTick
        if (elapsed < delayTicks) return false
        apply()
        return true
    }

    private fun stepTransform(
        handle: HologramHandle,
        transition: HologramTransition.Transform,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        val from = fromState?.transform ?: HologramTransform()
        val (factor, complete) = factorAt(startTick, transition.durationTicks, tick, transition.easing)
        val target = transition.targetTransform
        val tweened = HologramTransform(
            translation = lerpVec3(from.translation, target.translation, factor),
            leftRotation = HologramRotationInterpolator.slerp(from.leftRotation, target.leftRotation, factor),
            scale = lerpVec3(from.scale, target.scale, factor),
            rightRotation = HologramRotationInterpolator.slerp(from.rightRotation, target.rightRotation, factor),
        )
        gateway.updateTransform(handle, tweened)
        return complete
    }

    private fun stepBrightness(
        handle: HologramHandle,
        transition: HologramTransition.Brightness,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        val from = fromState?.brightness
        val (factor, complete) = factorAt(startTick, transition.durationTicks, tick, transition.easing)
        val tweened = lerpBrightness(from, transition.targetBrightness, factor)
        gateway.updateBrightness(handle, tweened)
        return complete
    }

    private fun stepGlowColor(
        handle: HologramHandle,
        transition: HologramTransition.GlowColor,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        val from = fromState?.glowColor
        val (factor, complete) = factorAt(startTick, transition.durationTicks, tick, transition.easing)
        val tweened = HologramColorInterpolator.lerp(from, transition.targetColor, factor)
        gateway.updateGlowColor(handle, tweened)
        return complete
    }

    private fun stepShadowRadius(
        handle: HologramHandle,
        transition: HologramTransition.ShadowRadius,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        val from = fromState?.shadowRadius ?: 0f
        val (factor, complete) = factorAt(startTick, transition.durationTicks, tick, transition.easing)
        val tweened = (from + (transition.targetRadius - from) * factor).toFloat()
        gateway.updateShadowRadius(handle, tweened)
        return complete
    }

    private fun stepShadowStrength(
        handle: HologramHandle,
        transition: HologramTransition.ShadowStrength,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        val from = fromState?.shadowStrength ?: 1f
        val (factor, complete) = factorAt(startTick, transition.durationTicks, tick, transition.easing)
        val tweened = (from + (transition.targetStrength - from) * factor).toFloat()
        gateway.updateShadowStrength(handle, tweened)
        return complete
    }

    private fun stepViewRange(
        handle: HologramHandle,
        transition: HologramTransition.ViewRange,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        val from = fromState?.viewRange ?: 64f
        val (factor, complete) = factorAt(startTick, transition.durationTicks, tick, transition.easing)
        val tweened = (from + (transition.targetRange - from) * factor).toFloat()
        gateway.updateViewRange(handle, tweened)
        return complete
    }

    private fun stepTextOpacity(
        handle: HologramHandle,
        transition: HologramTransition.TextOpacity,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        val from = (fromState?.textOpacity ?: 0).toInt()
        val (factor, complete) = factorAt(startTick, transition.durationTicks, tick, transition.easing)
        val target = transition.targetOpacity.toInt()
        val raw = from + (target - from) * factor
        val tweened = raw.roundToInt().coerceIn(BYTE_MIN, BYTE_MAX).toByte()
        gateway.updateTextOpacity(handle, tweened)
        return complete
    }

    private fun stepTextBackgroundColor(
        handle: HologramHandle,
        transition: HologramTransition.TextBackgroundColor,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        val from = fromState?.textBackgroundColor
        val (factor, complete) = factorAt(startTick, transition.durationTicks, tick, transition.easing)
        val tweened = HologramColorInterpolator.lerp(from, transition.targetColor, factor)
        gateway.updateTextBackgroundColor(handle, tweened)
        return complete
    }

    private fun stepTextLineWidth(
        handle: HologramHandle,
        transition: HologramTransition.TextLineWidth,
        fromState: TweenStartState?,
        startTick: Long,
        tick: Long,
    ): Boolean {
        val from = (fromState?.textLineWidth ?: 200).toDouble()
        val (factor, complete) = factorAt(startTick, transition.durationTicks, tick, transition.easing)
        val raw = from + (transition.targetWidth - from) * factor
        // lineWidth has a constructor range [1, 1000] on HologramKind.Text — we
        // clamp here so the tween never emits an out-of-range value that the
        // adapter would have to reject. Easing curves may overshoot.
        val tweened = raw.roundToInt().coerceIn(LINE_WIDTH_MIN, LINE_WIDTH_MAX)
        gateway.updateTextLineWidth(handle, tweened)
        return complete
    }

    private fun factorAt(
        startTick: Long,
        durationTicks: Int,
        tick: Long,
        easing: com.astarworks.astera.core.domain.animation.Easing,
    ): Pair<Double, Boolean> {
        if (durationTicks <= 0) {
            return 1.0 to true
        }
        val elapsed = tick - startTick
        if (elapsed >= durationTicks) return 1.0 to true
        if (elapsed <= 0L) return easing.apply(0.0) to false
        val rawT = elapsed.toDouble() / durationTicks.toDouble()
        return easing.apply(rawT) to false
    }

    private fun lerpVec3(from: Vec3, to: Vec3, factor: Double): Vec3 = Vec3(
        from.x + (to.x - from.x) * factor,
        from.y + (to.y - from.y) * factor,
        from.z + (to.z - from.z) * factor,
    )

    private fun lerpBrightness(
        from: HologramBrightness?,
        to: HologramBrightness?,
        factor: Double,
    ): HologramBrightness? {
        if (from == null && to == null) return null
        val a = from ?: HologramBrightness(0, 0)
        val b = to ?: HologramBrightness(0, 0)
        val block = (a.blockLight + (b.blockLight - a.blockLight) * factor).roundToInt().coerceIn(0, 15)
        val sky = (a.skyLight + (b.skyLight - a.skyLight) * factor).roundToInt().coerceIn(0, 15)
        return HologramBrightness(blockLight = block, skyLight = sky)
    }

    // ─── Validation + entry construction ────────────────────────────────

    private fun validateKindCompatibility(
        hologram: Hologram,
        transition: HologramTransition,
    ): HologramTransitionError.TypeMismatch? {
        val expectedKindClass: kotlin.reflect.KClass<out HologramKind>? = when (transition) {
            is HologramTransition.TextContent,
            is HologramTransition.TextOpacity,
            is HologramTransition.TextBackgroundColor,
            is HologramTransition.TextLineWidth,
            is HologramTransition.TextAlignmentSet,
            is HologramTransition.TextSeeThrough,
            is HologramTransition.TextShadowed,
            is HologramTransition.TextDefaultBackground,
            -> HologramKind.Text::class
            is HologramTransition.ItemSwap,
            is HologramTransition.ItemDisplayModeSet,
            -> HologramKind.Item::class
            is HologramTransition.BlockData -> HologramKind.Block::class
            is HologramTransition.Composite -> {
                // recurse — composite is incompatible if ANY child is incompatible
                for (child in transition.transitions) {
                    val failure = validateKindCompatibility(hologram, child)
                    if (failure != null) return failure
                }
                null
            }
            // Non-per-type transitions accept any kind.
            else -> null
        }
        if (expectedKindClass == null) return null
        val actualKindClass = hologram.kind::class
        return if (expectedKindClass.isInstance(hologram.kind)) {
            null
        } else {
            HologramTransitionError.TypeMismatch(
                expected = expectedKindClass,
                actual = actualKindClass,
            )
        }
    }

    private fun buildEntry(
        handle: HologramHandle,
        transitionHandle: HologramTransitionHandle,
        transition: HologramTransition,
        hologram: Hologram,
        startTick: Long,
    ): ActiveEntry = when (transition) {
        is HologramTransition.Composite -> {
            // Each child gets its own child-handle (not externally visible)
            // and the parent tracks pendingChildren for completion detection.
            val children = transition.transitions.map { childTransition ->
                buildSingleEntry(
                    handle = handle,
                    transitionHandle = mintHandle(),
                    transition = childTransition,
                    hologram = hologram,
                    startTick = startTick,
                )
            }
            CompositeEntry(
                handle = handle,
                transitionHandle = transitionHandle,
                startTick = startTick,
                children = children,
                pendingChildren = children.map { it.transitionHandle }.toMutableSet(),
            )
        }
        else -> buildSingleEntry(handle, transitionHandle, transition, hologram, startTick)
    }

    private fun buildSingleEntry(
        handle: HologramHandle,
        transitionHandle: HologramTransitionHandle,
        transition: HologramTransition,
        hologram: Hologram,
        startTick: Long,
    ): SingleEntry = SingleEntry(
        handle = handle,
        transitionHandle = transitionHandle,
        startTick = startTick,
        transition = transition,
        fromState = captureStartState(hologram, transition),
    )

    private fun captureStartState(
        hologram: Hologram,
        transition: HologramTransition,
    ): TweenStartState? {
        val textKind = hologram.kind as? HologramKind.Text
        return when (transition) {
            is HologramTransition.Transform -> TweenStartState(
                transform = hologram.transform,
                brightness = null, glowColor = null, shadowRadius = null, shadowStrength = null,
                viewRange = null, textOpacity = null, textBackgroundColor = null, textLineWidth = null,
            )
            is HologramTransition.Brightness -> TweenStartState(
                transform = null, brightness = hologram.brightness, glowColor = null,
                shadowRadius = null, shadowStrength = null, viewRange = null,
                textOpacity = null, textBackgroundColor = null, textLineWidth = null,
            )
            is HologramTransition.GlowColor -> TweenStartState(
                transform = null, brightness = null, glowColor = hologram.glowColor,
                shadowRadius = null, shadowStrength = null, viewRange = null,
                textOpacity = null, textBackgroundColor = null, textLineWidth = null,
            )
            is HologramTransition.ShadowRadius -> TweenStartState(
                transform = null, brightness = null, glowColor = null,
                shadowRadius = hologram.shadowRadius, shadowStrength = null, viewRange = null,
                textOpacity = null, textBackgroundColor = null, textLineWidth = null,
            )
            is HologramTransition.ShadowStrength -> TweenStartState(
                transform = null, brightness = null, glowColor = null,
                shadowRadius = null, shadowStrength = hologram.shadowStrength, viewRange = null,
                textOpacity = null, textBackgroundColor = null, textLineWidth = null,
            )
            is HologramTransition.ViewRange -> TweenStartState(
                transform = null, brightness = null, glowColor = null,
                shadowRadius = null, shadowStrength = null, viewRange = hologram.viewRangeBlocks,
                textOpacity = null, textBackgroundColor = null, textLineWidth = null,
            )
            is HologramTransition.TextOpacity -> TweenStartState(
                transform = null, brightness = null, glowColor = null,
                shadowRadius = null, shadowStrength = null, viewRange = null,
                textOpacity = textKind?.textOpacity, textBackgroundColor = null, textLineWidth = null,
            )
            is HologramTransition.TextBackgroundColor -> TweenStartState(
                transform = null, brightness = null, glowColor = null,
                shadowRadius = null, shadowStrength = null, viewRange = null,
                textOpacity = null, textBackgroundColor = textKind?.backgroundColor, textLineWidth = null,
            )
            is HologramTransition.TextLineWidth -> TweenStartState(
                transform = null, brightness = null, glowColor = null,
                shadowRadius = null, shadowStrength = null, viewRange = null,
                textOpacity = null, textBackgroundColor = null, textLineWidth = textKind?.lineWidth,
            )
            // Categorical / Composite: no continuous from-state needed.
            else -> null
        }
    }

    /**
     * Read the live hologram state through the gateway's
     * [IHologramGateway.snapshot] accessor (Wave 5transitions, ADR-0115
     * §A added this to the port surface specifically so the transition
     * controller could validate `transition.kind` against the live
     * `hologram.kind` and capture the from-state for smooth-tween
     * interpolation without needing a side-channel to the gateway impl).
     */
    private fun lookupHologram(handle: HologramHandle): Hologram? = gateway.snapshot(handle)

    private fun register(transitionHandle: HologramTransitionHandle, entry: ActiveEntry) {
        active[transitionHandle] = entry
        byHologram.computeIfAbsent(entry.handle) { ConcurrentHashMap.newKeySet() }
            .add(transitionHandle)
    }

    private fun unregisterFromHologramIndex(handle: HologramHandle, transitionHandle: HologramTransitionHandle) {
        byHologram[handle]?.let { set ->
            set.remove(transitionHandle)
            if (set.isEmpty()) byHologram.remove(handle)
        }
    }

    private fun mintHandle(): HologramTransitionHandle = HologramTransitionHandle(UUID.randomUUID().toString())

    private companion object {
        const val REASON_MANUAL = "manual"
        const val REASON_HOLOGRAM_DESPAWNED = "hologram_despawned"
        /**
         * Reserved discriminator for an explicit "this timeline naturally
         * ended" cancellation (Wave 5transitions, ADR-0115 §G). Not used
         * yet — timeline-natural completion publishes [HologramTransitionCompleted]
         * instead of Cancelled, so this constant exists for the spec contract
         * and forthcoming "loop early-exit" semantics in a Wave 6 follow-up.
         */
        @Suppress("unused")
        const val REASON_TIMELINE_COMPLETED = "timeline_completed"
        const val REASON_SCHEDULER_SHUTDOWN = "scheduler_shutdown"
        const val BYTE_MIN = -128
        const val BYTE_MAX = 127
        const val LINE_WIDTH_MIN = 1
        const val LINE_WIDTH_MAX = 1000
    }
}
