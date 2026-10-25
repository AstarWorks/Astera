package com.astarworks.astera.core.domain.world

/**
 * Vendor-neutral classification for how a player interacted with a block
 * (Wave 5world-events, ADR-0111).
 *
 * Used by [com.astarworks.astera.core.domain.event.PlayerInteractedBlock] to
 * give the skill-trigger router, custom-block listener (Wave 5x), and region
 * permission service (Wave 5n) enough context to dispatch without re-deriving
 * intent from raw Bukkit action enums.
 *
 * Sealed interface (not enum) so [PhysicalContact] can grow a thin
 * `pressure: Pressure` field in a future wave (pressure plates with weight)
 * without breaking exhaustive `when` callers — sealed addition would force
 * a compile-time visit, which is the desired forward-compatibility shape per
 * the Phase 1 pre-release posture.
 */
public sealed interface BlockInteractAction {

    /**
     * Main-hand right click on the block (open chest, activate lever,
     * launch skill bound to right click).
     */
    public data object RightClick : BlockInteractAction

    /**
     * Main-hand left click on the block (mining start, skill bound to left
     * click). Distinct from [PhysicalContact] because left-click triggers
     * even before the block actually breaks.
     */
    public data object LeftClick : BlockInteractAction

    /**
     * Player physically touched the block without clicking (stepping on a
     * pressure plate, walking through a tripwire, brushing a custom trigger
     * surface).
     */
    public data object PhysicalContact : BlockInteractAction

    /**
     * Off-hand use against the block (e.g., shield raise while looking at
     * a block, off-hand torch placement). Adapters may map several Bukkit
     * sub-cases here when they share the same domain meaning.
     */
    public data object OffHandUse : BlockInteractAction
}
