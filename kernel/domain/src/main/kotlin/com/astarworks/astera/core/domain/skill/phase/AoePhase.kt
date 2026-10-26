package com.astarworks.astera.core.domain.skill.phase

/**
 * Lifecycle phases of an AOE-archetype skill.
 *
 *  - [CAST] — at cast time, before the AOE radius is evaluated.
 *  - [HIT_ENTITY] — once per entity inside the AOE.
 *  - [EXPIRE] — when the AOE despawns (single-tick AOEs fire this right after [HIT_ENTITY]).
 *
 * Wave _skills-2 / ADR-0077.
 */
public enum class AoePhase { CAST, HIT_ENTITY, EXPIRE }
