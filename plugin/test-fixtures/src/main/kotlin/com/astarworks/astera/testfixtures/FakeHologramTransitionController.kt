package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.hologram.HologramTransitionError
import com.astarworks.astera.core.application.port.outbound.hologram.IHologramTransitionController
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.transition.HologramTimeline
import com.astarworks.astera.core.domain.hologram.transition.HologramTransition
import com.astarworks.astera.core.domain.hologram.transition.HologramTransitionHandle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-fixture [IHologramTransitionController] (Wave 5transitions,
 * ADR-0115 §I).
 *
 * Records every `play / cancel / cancelAll` call into a public log so
 * tests can assert on call shape + ordering without spinning up a real
 * `IScheduler`. Unlike
 * [com.astarworks.astera.core.application.service.hologram.InMemoryHologramTransitionController],
 * this fake **does not step transitions** — it stores the play call and
 * leaves the test to assert on what was scheduled.
 *
 * Use this fake in unit tests where you want to verify "the use case
 * called `play(handle, transition)` with the expected transition" without
 * exercising the controller's tick math. For tests that need to assert
 * on the actual tween output, use `InMemoryHologramTransitionController`
 * with a fake `IScheduler` whose `currentTick()` you control.
 *
 * Test cleanup: call [clear] in `@AfterEach` if reusing one instance
 * across tests.
 */
public class FakeHologramTransitionController : IHologramTransitionController {

    /**
     * Recorded controller operations. Tests assert on this list to verify
     * the use case called the controller with the expected arguments in
     * the expected order.
     */
    public sealed interface Op {
        public data class PlayedTransition(
            val handle: HologramHandle,
            val transitionHandle: HologramTransitionHandle,
            val transition: HologramTransition,
        ) : Op

        public data class PlayedTimeline(
            val handle: HologramHandle,
            val transitionHandle: HologramTransitionHandle,
            val timeline: HologramTimeline,
        ) : Op

        public data class Cancelled(val transitionHandle: HologramTransitionHandle) : Op
        public data class CancelledAll(val handle: HologramHandle) : Op
    }

    private val ops: MutableList<Op> = mutableListOf()

    private val activeByHologram: ConcurrentHashMap<HologramHandle, MutableSet<HologramTransitionHandle>> =
        ConcurrentHashMap()
    private val hologramByTransition: ConcurrentHashMap<HologramTransitionHandle, HologramHandle> =
        ConcurrentHashMap()

    /**
     * Per-test scripted failure for the next [play] call. Set this to make
     * the next `play(transition)` or `play(timeline)` return [Result.failure]
     * instead of recording a successful play. Auto-cleared after one use.
     */
    public var scriptedNextFailure: HologramTransitionError? = null

    override fun play(
        handle: HologramHandle,
        transition: HologramTransition,
    ): Result<HologramTransitionHandle, HologramTransitionError> {
        scriptedNextFailure?.let { failure ->
            scriptedNextFailure = null
            return Result.failure(failure)
        }
        val transitionHandle = mintHandle()
        ops += Op.PlayedTransition(handle, transitionHandle, transition)
        registerActive(handle, transitionHandle)
        return Result.success(transitionHandle)
    }

    override fun play(
        handle: HologramHandle,
        timeline: HologramTimeline,
    ): Result<HologramTransitionHandle, HologramTransitionError> {
        scriptedNextFailure?.let { failure ->
            scriptedNextFailure = null
            return Result.failure(failure)
        }
        val transitionHandle = mintHandle()
        ops += Op.PlayedTimeline(handle, transitionHandle, timeline)
        registerActive(handle, transitionHandle)
        return Result.success(transitionHandle)
    }

    override fun cancel(
        transitionHandle: HologramTransitionHandle,
    ): Result<Unit, HologramTransitionError> {
        ops += Op.Cancelled(transitionHandle)
        val owner = hologramByTransition.remove(transitionHandle)
        if (owner != null) {
            activeByHologram[owner]?.let { set ->
                set.remove(transitionHandle)
                if (set.isEmpty()) activeByHologram.remove(owner)
            }
        }
        return Result.success(Unit)
    }

    override fun cancelAll(handle: HologramHandle): Result<Unit, HologramTransitionError> {
        ops += Op.CancelledAll(handle)
        val handles = activeByHologram.remove(handle) ?: return Result.success(Unit)
        for (transitionHandle in handles) {
            hologramByTransition.remove(transitionHandle)
        }
        return Result.success(Unit)
    }

    override fun active(handle: HologramHandle): Collection<HologramTransitionHandle> {
        return activeByHologram[handle]?.toList() ?: emptyList()
    }

    /** Test accessor: every operation invoked, in call order. */
    public fun operations(): List<Op> = ops.toList()

    /**
     * Manually advance the controller's scripted state — drops [transitionHandle]
     * from the active set so subsequent `active(handle)` calls reflect the
     * completion / cancellation without recording another op. Pair this with
     * direct event-bus assertions if you need to verify event publishing
     * (the fake does NOT publish events — wire your use case to a real
     * `InMemoryDomainEventBus` for those checks).
     *
     * Idempotent: advancing an unknown handle is a no-op.
     */
    public fun manualAdvance(transitionHandle: HologramTransitionHandle) {
        val owner = hologramByTransition.remove(transitionHandle) ?: return
        activeByHologram[owner]?.let { set ->
            set.remove(transitionHandle)
            if (set.isEmpty()) activeByHologram.remove(owner)
        }
    }

    /** Test cleanup hook — drops op-history + active sets. */
    public fun clear() {
        ops.clear()
        activeByHologram.clear()
        hologramByTransition.clear()
        scriptedNextFailure = null
    }

    private fun registerActive(handle: HologramHandle, transitionHandle: HologramTransitionHandle) {
        activeByHologram.computeIfAbsent(handle) { ConcurrentHashMap.newKeySet() }
            .add(transitionHandle)
        hologramByTransition[transitionHandle] = handle
    }

    private fun mintHandle(): HologramTransitionHandle =
        HologramTransitionHandle(UUID.randomUUID().toString())
}
