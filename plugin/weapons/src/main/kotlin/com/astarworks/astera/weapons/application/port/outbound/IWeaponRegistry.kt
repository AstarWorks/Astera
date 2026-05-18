package com.astarworks.astera.weapons.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.weapons.domain.weapon.WeaponId
import com.astarworks.astera.weapons.domain.weapon.WeaponSpec

/**
 * Outbound port: read-only lookup of registered weapons.
 *
 * The registry is populated at plugin startup by `WeaponContents.bootstrap`
 * (Wave _infra-2a, ADR-0060) and hot-reloaded by `/astera-weapons reload`.
 * Phase 4 (UGC marketplace) may swap to a DB-backed `Contents<WeaponId,
 * WeaponSpec>` implementation without touching application code.
 *
 * Extends [Contents] so the substrate's `find` / `all` contract is shared with
 * every other content type (items, skills, …). Adds no methods of its own —
 * the typealias-like relationship lets call sites stay typed at
 * `IWeaponRegistry` while the impl participates in `/astera-weapons reload`.
 */
public interface IWeaponRegistry : Contents<WeaponId, WeaponSpec>
