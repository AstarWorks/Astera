package com.astarworks.astera.weapons.testfixtures

import com.astarworks.astera.weapons.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.weapons.domain.weapon.WeaponId
import com.astarworks.astera.weapons.domain.weapon.WeaponSpec

/**
 * In-memory [IWeaponRegistry] for use case tests.
 *
 * ADR-0022 Phase 3: lifted out of `:plugin:test-fixtures` (which is Core-only)
 * into the Weapons plugin's own test source set so test-fixtures stays free
 * of weapon-domain references.
 *
 * Use [register] to add specs; [find] / [all] reflect what has been registered.
 */
public class FakeWeaponRegistry : IWeaponRegistry {
    private val store: MutableMap<WeaponId, WeaponSpec> = mutableMapOf()

    public fun register(spec: WeaponSpec) {
        store[spec.id] = spec
    }

    override fun find(id: WeaponId): WeaponSpec? = store[id]

    override fun all(): Collection<WeaponSpec> = store.values.toList()
}
