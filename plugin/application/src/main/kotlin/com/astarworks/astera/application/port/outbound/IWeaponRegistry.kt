package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.weapon.WeaponId
import com.astarworks.astera.domain.model.weapon.WeaponSpec

/**
 * Outbound port: read-only lookup of registered weapons.
 *
 * The registry is populated at plugin startup by `WeaponLoaderService`. Phase 1
 * uses an in-memory implementation; Phase 4 (UGC marketplace) may swap to a
 * DB-backed one without touching application code.
 */
interface IWeaponRegistry {
    fun find(id: WeaponId): WeaponSpec?
    fun all(): Collection<WeaponSpec>
}
