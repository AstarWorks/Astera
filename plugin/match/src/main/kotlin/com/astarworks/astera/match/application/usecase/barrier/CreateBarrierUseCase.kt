package com.astarworks.astera.match.application.usecase.barrier

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import com.astarworks.astera.match.domain.barrier.Barrier

/**
 * Spawn a [Barrier] in a match (Wave 5d).
 *
 * Validates the request, stamps `createdAtTick` from the scheduler, and
 * registers the barrier with the [IBarrierGateway] (which defaults its
 * status to `Active`). Failures surface through the sealed
 * [CreateBarrierError] hierarchy so callers handle them exhaustively
 * instead of catching exceptions.
 *
 * # Validation
 *
 * Wave 5d ships the **minimal** rule set:
 *
 * - `region.volume > 0` — a zero-volume region cannot hold blocks; reject
 *   with [CreateBarrierError.EmptyRegion]. `Region`'s `init` already
 *   guarantees `min.* <= max.*`, so only the degenerate "single point /
 *   line / plane" cases reach this check.
 * - `slowdownPercent in 0.0..1.0` — `Barrier`'s `init` enforces this with
 *   `IllegalArgumentException`. We check first and return a typed
 *   [CreateBarrierError.InvalidSlowdown] so use-case callers stay inside
 *   the `Result<T, E>` contract (no leaked JVM throwables, per the
 *   application-layer Konsist rule).
 *
 * Region-overlap detection ("two barriers fighting for the same blocks")
 * is a Wave 6+ stretch — the spec calls it out explicitly. For Phase 1
 * PoC, two overlapping barriers simply coexist in the gateway; the visual
 * layer (Wave 6 `PaperBarrierGateway`) will Z-fight harmlessly.
 *
 * # Tick stamp
 *
 * `createdAtTick = scheduler.currentTick()` so future use cases
 * (`BarrierStatusScheduler` derived effects, sabotage-cooldown checks,
 * end-of-match cleanup ordering) can reason about age without holding a
 * separate clock.
 */
public class CreateBarrierUseCase(
    private val gateway: IBarrierGateway,
    private val scheduler: IScheduler,
) {

    public fun execute(
        id: BarrierId,
        region: Region,
        origin: Vec3,
        creator: PlayerId,
        slowdownPercent: Double = DEFAULT_SLOWDOWN_PERCENT,
        detectionEnabled: Boolean = true,
    ): Result<Barrier, CreateBarrierError> {
        if (region.volume <= 0.0) {
            return Result.failure(CreateBarrierError.EmptyRegion(region))
        }
        if (slowdownPercent !in 0.0..1.0) {
            return Result.failure(CreateBarrierError.InvalidSlowdown(slowdownPercent))
        }

        val barrier = Barrier(
            id = id,
            region = region,
            origin = origin,
            creator = creator,
            createdAtTick = scheduler.currentTick(),
            slowdownPercent = slowdownPercent,
            detectionEnabled = detectionEnabled,
        )
        gateway.spawn(barrier)
        return Result.success(barrier)
    }

    public companion object {
        /**
         * Default fraction of block-destruction damage absorbed by an
         * `Active` barrier (25%). Mirrors the design doc; per-content
         * overrides can pass [execute]'s `slowdownPercent` parameter
         * explicitly.
         */
        public const val DEFAULT_SLOWDOWN_PERCENT: Double = 0.25
    }
}

/**
 * Sealed error hierarchy for [CreateBarrierUseCase].
 *
 * Each variant carries a [MessageKey] so `IMessageRenderer` can localize
 * the failure for the player without the application layer touching
 * Adventure / Bukkit text APIs.
 */
public sealed class CreateBarrierError {
    public abstract val messageKey: MessageKey

    /** The region had zero (or negative) volume — nothing to enclose. */
    public data class EmptyRegion(val region: Region) : CreateBarrierError() {
        override val messageKey: MessageKey = MessageKey("astera.match.barrier.error.empty-region")
    }

    /** `slowdownPercent` was outside `0.0..1.0`. */
    public data class InvalidSlowdown(val value: Double) : CreateBarrierError() {
        override val messageKey: MessageKey = MessageKey("astera.match.barrier.error.invalid-slowdown")
    }
}
