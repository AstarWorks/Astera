package com.astarworks.astera.combat.domain.damage

import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.core.domain.damage.DamageAttribute

/**
 * A single damage-reduction event applied during [DamageRule.resolve].
 *
 * One [MitigationApplied] is recorded per mitigation source, NOT per damage
 * event — armor with 4 slots equipped emits 4 entries; a 5-piece set bonus
 * adds a 5th; a future shield block adds another. `ApplyDamageUseCase`
 * publishes a `DamageMitigated` domain event per entry so analytics can
 * attribute reductions to their source.
 */
public data class MitigationApplied(
    val reason: MitigationReason,
    val amountReduced: Double,
) {
    init {
        require(amountReduced >= 0.0) { "amountReduced must be >= 0: $amountReduced" }
    }
}

/** Why a chunk of damage was mitigated. */
public sealed class MitigationReason {
    public data class Armor(val slot: ArmorSlot, val percent: Double) : MitigationReason()
    public data class ArmorSet(val setKey: String, val percent: Double) : MitigationReason()
    public data object Block : MitigationReason()
    public data class Resist(val attribute: DamageAttribute, val percent: Double) : MitigationReason()
}
