package com.astarworks.astera.core.domain.survival

/**
 * Family of a [GatheringSpot] — selects which [GatheringSkillTrigger] is
 * valid against it (Wave 5pve-survival / ADR-0107).
 *
 * # Why a sealed interface (not enum)
 *
 * Future kinds (e.g. `BeeNest(honeyCharge: Int)`, `Crystalline(rarity:
 * Int)`) may want per-kind payload. Sealed interface keeps the
 * exhaustiveness without locking out parameterised variants. The current
 * three primitives map 1:1 to the `Fishing` / `Mining` / `Logging`
 * trigger families.
 */
public sealed interface GatheringSpotKind {

    /** Fishing rod-only spots (water surface, dock, lava pool variant). */
    public data object Fishing : GatheringSpotKind

    /** Pickaxe-only ore / stone veins. */
    public data object Mining : GatheringSpotKind

    /** Axe-only tree / log nodes. */
    public data object Logging : GatheringSpotKind

    /** Shovel-only loam / sand / clay nodes. */
    public data object Digging : GatheringSpotKind
}
