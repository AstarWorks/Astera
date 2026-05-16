package com.astarworks.astera.testfixtures

import com.astarworks.astera.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.domain.model.weapon.WeaponId
import com.astarworks.astera.domain.model.weapon.WeaponSpec

/**
 * In-memory [IWeaponRegistry] for use case tests.
 *
 * Use [register] to add specs; [find] / [all] reflect what has been registered.
 */
class FakeWeaponRegistry : IWeaponRegistry {
    private val store: MutableMap<WeaponId, WeaponSpec> = mutableMapOf()

    fun register(spec: WeaponSpec) {
        store[spec.id] = spec
    }

    override fun find(id: WeaponId): WeaponSpec? = store[id]

    override fun all(): Collection<WeaponSpec> = store.values.toList()
}
