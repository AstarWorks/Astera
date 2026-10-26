package com.astarworks.astera.core.domain.skill.phase

/**
 * Lifecycle phases of a buff-archetype skill.
 *
 *  - [CAST] — when the buff is first applied.
 *  - [EXPIRE] — when the buff timer reaches zero.
 *  - [REMOVED] — when the buff is stripped early (cleanse, death, dispel).
 *
 * Wave _skills-2 / ADR-0077.
 */
public enum class BuffPhase { CAST, EXPIRE, REMOVED }
