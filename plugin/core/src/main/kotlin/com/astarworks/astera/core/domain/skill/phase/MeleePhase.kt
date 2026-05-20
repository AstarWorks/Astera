package com.astarworks.astera.core.domain.skill.phase

/**
 * Lifecycle phases of a melee-archetype skill.
 *
 *  - [CAST] — swing initiation.
 *  - [HIT_ENTITY] — connected with a living entity.
 *  - [MISS] — swing finished without finding any target.
 *
 * Wave _skills-2 / ADR-0077.
 */
public enum class MeleePhase { CAST, HIT_ENTITY, MISS }
