package com.astarworks.astera.application.service

import com.astarworks.astera.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.domain.model.weapon.WeaponId
import com.astarworks.astera.domain.model.weapon.WeaponSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory weapon registry. Populated by [WeaponLoaderService] at startup.
 *
 * Phase 4 may swap to a DB-backed implementation that reads from
 * `persistence-postgres` — the application layer only depends on
 * [IWeaponRegistry], so the platform module's Koin module is the only place
 * that changes.
 */
class MutableWeaponRegistry : IWeaponRegistry {
    private val store = ConcurrentHashMap<WeaponId, WeaponSpec>()

    fun register(spec: WeaponSpec) {
        store[spec.id] = spec
    }

    fun clear() {
        store.clear()
    }

    override fun find(id: WeaponId): WeaponSpec? = store[id]
    override fun all(): Collection<WeaponSpec> = store.values.toList()
}
