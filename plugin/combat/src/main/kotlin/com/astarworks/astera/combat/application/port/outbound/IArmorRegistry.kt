package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorSet
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSpec

/**
 * Outbound: read-only lookup of armor specs and set definitions.
 *
 * Worker α provides `MutableArmorRegistry` (mirrors `MutableSkillRegistry`)
 * + `ArmorLoaderService` (YAML → ArmorSpec / ArmorSet).
 */
public interface IArmorRegistry {
    public fun find(id: ArmorId): ArmorSpec?

    public fun findSet(key: ArmorSetKey): ArmorSet?

    public fun all(): Collection<ArmorSpec>

    public fun allSets(): Collection<ArmorSet>
}
