package com.astarworks.astera.match.domain.machine

/**
 * Sealed enumeration of [TrapMachine] effects (Wave 5d, P2 minimal).
 *
 * Phase 1 PoC ships [Pitfall] only — the simplest trap to demonstrate the
 * `TrapMachine` pipeline end-to-end (entity walks onto the tile, falls
 * through). Wave 6+ will add e.g. `Knockback`, `StatusInflict`, `Teleport`.
 *
 * Each variant is a `data object` so equality / `when` exhaustiveness
 * behave naturally and the compiler flags every consumer that hasn't
 * handled a newly-added variant.
 */
public sealed class TrapEffect {

    /**
     * Open the tile beneath the entity, letting it fall through. The Wave 6
     * adapter will animate this by temporarily replacing the block at
     * [TrapMachine.position] with air for a fixed window, then restoring
     * via the block-change journal (Wave 5d worker η).
     */
    public data object Pitfall : TrapEffect()
}
