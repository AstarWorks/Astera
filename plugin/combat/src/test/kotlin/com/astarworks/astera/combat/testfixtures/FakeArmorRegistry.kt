package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorSet
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSpec

/**
 * In-memory [IArmorRegistry] for use case + modifier tests.
 *
 * Call [register] / [registerSet] to seed content; [find] / [findSet] /
 * [all] / [allSets] reflect the registered values.
 */
public class FakeArmorRegistry : IArmorRegistry {
    private val pieces: MutableMap<ArmorId, ArmorSpec> = mutableMapOf()
    private val sets: MutableMap<ArmorSetKey, ArmorSet> = mutableMapOf()

    public fun register(spec: ArmorSpec) {
        pieces[spec.id] = spec
    }

    public fun registerSet(set: ArmorSet) {
        sets[set.key] = set
    }

    override fun find(id: ArmorId): ArmorSpec? = pieces[id]
    override fun findSet(key: ArmorSetKey): ArmorSet? = sets[key]
    override fun all(): Collection<ArmorSpec> = pieces.values.toList()
    override fun allSets(): Collection<ArmorSet> = sets.values.toList()
}
