package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorSet
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.core.application.contents.Contents

/**
 * Outbound port: read-only lookup of armor pieces and set definitions.
 *
 * Extends [Contents] for the pieces side so `find(id) / all()` is shared with
 * every other content type. Set-level lookups ([findSet] / [allSets]) stay as
 * named methods because sets live in a parallel map keyed by [ArmorSetKey] —
 * a single `Contents<K, V>` substrate could only model one of them.
 *
 * Populated at plugin startup by `ArmorContents.bootstrap` (Wave _infra-2b,
 * ADR-0065) and hot-reloaded atomically across both maps by the cross-plugin
 * `/astera reload armor` dispatch.
 */
public interface IArmorRegistry : Contents<ArmorId, ArmorSpec> {
    public fun findSet(key: ArmorSetKey): ArmorSet?

    public fun allSets(): Collection<ArmorSet>
}
