package com.astarworks.astera.core.domain.animation.mining

/**
 * Why a mining session was cancelled before the block broke
 * (Wave 5animation, ADR-0113).
 *
 * Sealed so future variants can carry payload (e.g. `BlockedByRegion`
 * with the offending region id) without breaking exhaustive callers.
 * Wave 6's `PaperMiningProgressDriver` maps player input → these variants
 * at the boundary; the domain layer stays free of Bukkit cause types.
 */
public sealed interface MiningCancelReason {

    /** Player released the left-click action. */
    public data object ReleasedInput : MiningCancelReason

    /** Player aimed at a different block before this one broke. */
    public data object SwitchedTarget : MiningCancelReason

    /**
     * Player switched their active hotbar tool to one the block won't
     * accept — vanilla cancels mining when the tool changes mid-break.
     */
    public data object SwitchedTool : MiningCancelReason

    /** Player moved out of break range (vanilla: 4.5 blocks). */
    public data object OutOfRange : MiningCancelReason

    /**
     * The system stopped the mine (match phase change, cinematic camera
     * lock, region permission update).
     *
     * @property detail short tag for the system reason (audit log will
     *   surface this).
     */
    public data class SystemStopped(public val detail: String) : MiningCancelReason
}
