package com.astarworks.astera.core.domain.survival

/**
 * Gathering-axis [SkillTrigger]-equivalent family (Wave 5pve-survival /
 * ADR-0107).
 *
 * Combat-axis triggers (`SkillTrigger.LeftClick`, `SkillTrigger.RightClick`,
 * …) bind to weapons and route through the items plugin. Gathering
 * triggers bind to gathering tools (fishing rod / pickaxe / axe / shovel)
 * and route through the survival plugin's gathering listener (Phase B of
 * Wave 6 Paper sweep — this wave only commits the trigger taxonomy).
 *
 * Keeping the family separate from the combat hierarchy means:
 *
 *  - `SkillTrigger.LeftClick` on a sword never accidentally routes to a
 *    Mining skill (combat / survival contexts stay disjoint).
 *  - Gathering tool skills can be exhaustively switched on without leaking
 *    `else -> error("not a gathering trigger")` branches into combat code.
 *  - Wave _items-1 [ItemSpec.skillBindings] adds a "trigger family" axis
 *    so an item author can mix combat + gathering triggers on the same
 *    spec when intentional (Phase 4 hybrid tools).
 */
public sealed interface GatheringSkillTrigger {

    /** Fishing rod cast (right-click while holding a rod). */
    public data object FishingRodCast : GatheringSkillTrigger

    /** Pickaxe swing against a [GatheringSpotKind.Mining] spot. */
    public data object PickaxeSwing : GatheringSkillTrigger

    /** Axe swing against a [GatheringSpotKind.Logging] spot. */
    public data object AxeSwing : GatheringSkillTrigger

    /** Shovel dig against a [GatheringSpotKind.Digging] spot. */
    public data object ShovelDig : GatheringSkillTrigger
}
