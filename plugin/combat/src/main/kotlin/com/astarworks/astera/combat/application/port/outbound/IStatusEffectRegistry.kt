package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.core.application.contents.Contents

/**
 * Outbound port: read-only lookup of all known status effect *definitions*.
 *
 * Distinct from `IStatusEffectContainer`, which holds per-entity *runtime*
 * instances — this port answers "what does effect 'burn' look like" while the
 * container answers "is player X currently burning". The split lets the
 * runtime container stay slim and per-entity while specs live globally.
 *
 * Populated at plugin startup by `StatusContents.bootstrap` (Wave _infra-2b,
 * ADR-0065) and hot-reloaded by the cross-plugin `/astera reload status`
 * dispatch. Phase 4 may swap the impl to a DB-backed lookup without touching
 * application code.
 *
 * Extends [Contents] so the substrate's `find` / `all` contract is shared with
 * every other content type. Adds no methods of its own.
 */
public interface IStatusEffectRegistry : Contents<StatusEffectId, StatusEffectSpec>
