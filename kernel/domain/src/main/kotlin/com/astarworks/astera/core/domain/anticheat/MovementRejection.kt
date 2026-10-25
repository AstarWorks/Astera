package com.astarworks.astera.core.domain.anticheat

/**
 * A definitive movement-validation failure. The validator considers the
 * movement physically impossible and asks the caller to cancel it.
 *
 * Distinct from [MovementSuspicion]: Suspicions report; Rejections block.
 */
public sealed interface MovementRejection {

    /**
     * Implausible distance for a single tick — a teleport or chunked
     * position update that suggests packet manipulation. [distance] is
     * the measured Euclidean delta.
     */
    public data class Teleport(val distance: Double) : MovementRejection

    /**
     * Position moved through a region that should be solid. Strictest case
     * of [MovementSuspicion.WallClipping] — the validator is confident the
     * move passed through a block.
     */
    public data object NoClip : MovementRejection
}
