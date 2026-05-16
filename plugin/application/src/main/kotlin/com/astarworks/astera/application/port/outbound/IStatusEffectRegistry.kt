package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.status.StatusEffectId
import com.astarworks.astera.domain.model.status.StatusEffectSpec

/**
 * Read-only registry of all known status effect *definitions*.
 *
 * Mirrors [ISkillRegistry] / [IWeaponRegistry]: populated at startup by a
 * `ContentLoader<StatusEffectYamlConfig>` driven service. Distinct from
 * [IStatusEffectContainer], which holds per-entity *runtime* instances —
 * this port answers "what does effect 'burn' look like" while the container
 * answers "is player X currently burning". The split lets the runtime
 * container stay slim and per-entity while specs live globally.
 *
 * Phase 4 may swap the impl to a DB-backed lookup without touching
 * application code.
 */
public interface IStatusEffectRegistry {
    public fun find(id: StatusEffectId): StatusEffectSpec?
    public fun all(): Collection<StatusEffectSpec>
}
