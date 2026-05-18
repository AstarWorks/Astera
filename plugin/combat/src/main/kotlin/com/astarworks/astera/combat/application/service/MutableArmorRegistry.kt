package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorSet
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory armor registry. Populated by [ArmorLoaderService] at startup; the
 * platform module wires both as singletons (Wave 5a, mirror of
 * [MutableSkillRegistry]).
 *
 * Stores armor pieces ([ArmorSpec]) and set definitions ([ArmorSet]) in two
 * separate maps so the cardinalities can diverge cleanly (e.g. a 30-piece
 * registry with 4 named sets). Phase 4 may swap to a DB-backed implementation;
 * the application layer only depends on [IArmorRegistry].
 */
public class MutableArmorRegistry : IArmorRegistry {
    private val pieces = ConcurrentHashMap<ArmorId, ArmorSpec>()
    private val sets = ConcurrentHashMap<ArmorSetKey, ArmorSet>()

    public fun register(spec: ArmorSpec) {
        pieces[spec.id] = spec
    }

    public fun registerSet(set: ArmorSet) {
        sets[set.key] = set
    }

    public fun clear() {
        pieces.clear()
        sets.clear()
    }

    override fun find(id: ArmorId): ArmorSpec? = pieces[id]

    override fun findSet(key: ArmorSetKey): ArmorSet? = sets[key]

    override fun all(): Collection<ArmorSpec> = pieces.values.toList()

    override fun allSets(): Collection<ArmorSet> = sets.values.toList()
}
