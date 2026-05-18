package com.astarworks.astera.weapons.application.service

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.weapons.application.config.WeaponYamlConfig
import com.astarworks.astera.weapons.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.weapons.domain.weapon.WeaponId
import com.astarworks.astera.weapons.domain.weapon.WeaponSpec

/**
 * Weapon-side `Contents<WeaponId, WeaponSpec>` (Wave _infra-2a, ADR-0060).
 *
 * Replaces the legacy `MutableWeaponRegistry` + `WeaponLoaderService` pair.
 * Inherits:
 *
 *   - `find(id) / all()` from [com.astarworks.astera.core.application.contents.Contents]
 *     (the `IWeaponRegistry` port surface that every weapon use case consumes)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera-weapons reload` (all-or-nothing, atomic swap)
 *   - `register(id, spec) / clear()` for tests + the `AsteraTestHarness`
 *
 * No state of its own — the substrate's `AtomicReference<Map<WeaponId,
 * WeaponSpec>>` is the single source of truth and is read by every
 * `IWeaponRegistry` consumer through `find()` (so concurrent readers always
 * see a self-consistent snapshot, never a torn map).
 */
public class WeaponContents : YamlDynamicContents<WeaponId, WeaponSpec, WeaponYamlConfig>(
    categoryName = "weapons",
    deserializer = WeaponYamlConfig.serializer(),
), IWeaponRegistry {

    /**
     * Convert each YAML DTO to its `(WeaponId, WeaponSpec)` entry. Delegates
     * to [WeaponYamlConfig.toSpec] so any future schema evolution stays
     * single-sourced in the DTO.
     */
    override fun toEntry(config: WeaponYamlConfig): Pair<WeaponId, WeaponSpec> {
        val spec = config.toSpec()
        return spec.id to spec
    }

    /**
     * Convenience overload: register by spec alone (the spec owns its id).
     * Used by `AsteraTestHarness.withWeapons(...)` and by future admin
     * commands that build a `WeaponSpec` in memory.
     */
    public fun register(spec: WeaponSpec) {
        register(spec.id, spec)
    }
}
