package com.astarworks.astera.match.domain.machine

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Marker for custom in-world machines (Wave 5d, RTM-PARITY-TRACKER issue #10
 * P2 minimal).
 *
 * Wave 5d ships the value types only — runtime use cases (placement,
 * activation, scheduling, persistence) are deferred to Wave 6+. The sealed
 * shape lets future waves add `SkillSpawner`, `Conveyor`, `Pressure Plate`
 * etc. without revising existing call sites (exhaustive `when` will fail-fast
 * at compile time on every consumer when a new variant is added).
 *
 * # Phase 1 PoC variants
 *
 * - [SkillCoreMachine] — fires a combat skill when its [TriggerCondition]
 *   evaluates true.
 * - [TrapMachine] — applies a [TrapEffect] to entities entering its tile.
 *
 * The single shared invariant is [position]: every machine occupies one
 * world cell. Multi-cell machines (cannons, factories) are explicitly
 * deferred — they'd require a different abstraction (machine *region*
 * rather than machine *point*).
 */
public sealed class Machine {
    /** World-space anchor cell of this machine. */
    public abstract val position: Vec3
}
