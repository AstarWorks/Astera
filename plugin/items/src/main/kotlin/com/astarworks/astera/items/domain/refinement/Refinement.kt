package com.astarworks.astera.items.domain.refinement

/**
 * One refinement layer applied to an [ItemInstance] (Wave 5refine, ADR-0100).
 *
 * Sealed because the 4-category 厳選 matrix is *closed* by design (HSB +
 * 原神 + MH + PoE summary). Adding a fifth category requires a deliberate ADR
 * + Konsist test update, not a quiet variant push.
 *
 * Each variant carries the minimum state needed to:
 *
 *   1. Recompute the layer's [com.astarworks.astera.core.domain.attribute.AttributeModifier]
 *      contribution against the instance's container.
 *   2. Round-trip through JSONB persistence (V30 `item_instances.layers`).
 *   3. Render a player-facing summary line in the inspect GUI (Wave 6).
 *
 * # Composition / multiplication semantics (ADR-0100 §Decision)
 *
 * Each layer contributes a *multiplicative* factor on the host stat:
 *
 *   - [Reforge.tier]: stat * (1 + tier * 0.1)  (placeholder; tunable per pool)
 *   - [Enchant.level]: stat * (1 + level * 0.05)
 *   - [Gemstone.slot]: stat * (1 + slot.bonus / 100)
 *   - [Star.stars]: stat * (1 + stars * 0.08)
 *   - [Recomb.score]: stat * (1 + score / 100)
 *
 * The actual coefficient choices live with the loot / balance service so the
 * domain stays free of magic constants. Domain just records the layer.
 *
 * # Why sealed in `items.domain` rather than `core.domain`
 *
 * Refinement is a per-item concern — Combat does not (yet) need to read the
 * sealed variants. ADR-0022 sibling-isolation: Combat reaches the *effective*
 * stat via the container's `effectiveNumber()` and stays oblivious to which
 * layer produced the modifier.
 */
public sealed class Refinement {

    /** HSB Reforge Stone style: one rolled tier from the item's `reforge_pool`. */
    public data class Reforge(
        public val poolEntry: String,
        public val tier: Int,
    ) : Refinement() {
        init {
            require(poolEntry.isNotBlank()) { "Refinement.Reforge.poolEntry must not be blank." }
            require(tier >= 1) { "Refinement.Reforge.tier must be >= 1, got $tier" }
        }
    }

    /** Vanilla-style enchant track; magnitude scales linearly with [level]. */
    public data class Enchant(
        public val enchantId: String,
        public val level: Int,
    ) : Refinement() {
        init {
            require(enchantId.isNotBlank()) { "Refinement.Enchant.enchantId must not be blank." }
            require(level >= 1) { "Refinement.Enchant.level must be >= 1, got $level" }
        }
    }

    /** HSB Hot Potato Book / Gemstone slot. [slotIndex] is 0-based and < `RefinementSpec.gemstoneSlots`. */
    public data class Gemstone(
        public val slotIndex: Int,
        public val gemId: String,
        public val tier: Int,
    ) : Refinement() {
        init {
            require(slotIndex >= 0) { "Refinement.Gemstone.slotIndex must be >= 0, got $slotIndex" }
            require(gemId.isNotBlank()) { "Refinement.Gemstone.gemId must not be blank." }
            require(tier >= 1) { "Refinement.Gemstone.tier must be >= 1, got $tier" }
        }
    }

    /** HSB Star track: deterministic linear progression 1..N. */
    public data class Star(
        public val stars: Int,
    ) : Refinement() {
        init {
            require(stars >= 1) { "Refinement.Star.stars must be >= 1, got $stars" }
        }
    }

    /** PoE Recombinator style: opaque [score] folded into the multiplier. */
    public data class Recomb(
        public val score: Int,
    ) : Refinement() {
        init {
            require(score >= 1) { "Refinement.Recomb.score must be >= 1, got $score" }
        }
    }
}
