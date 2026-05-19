package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Outbound port: read-only lookup of registered skills.
 *
 * The registry is populated at plugin startup by `SkillContents.bootstrap`
 * (Wave _infra-2b, ADR-0065) and hot-reloaded by the cross-plugin
 * `/astera reload skills` dispatch. Phase 4 may swap to a DB-backed
 * `Contents<SkillId, SkillSpec>` implementation without touching application
 * code.
 *
 * Extends [Contents] so the substrate's `find` / `all` contract is shared with
 * every other content type (weapons, items, status, armor, …). Adds no methods
 * of its own — the typealias-like relationship lets call sites stay typed at
 * `ISkillRegistry` while the impl participates in the reload registry.
 */
public interface ISkillRegistry : Contents<SkillId, SkillSpec>
