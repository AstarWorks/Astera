package com.astarworks.astera.core.domain.skill.phase

/**
 * Lifecycle phases of a summon-archetype skill.
 *
 *  - [CAST] — at cast time, before any summon spawns.
 *  - [SPAWN] — once per summoned entity at spawn time.
 *  - [SUMMON_DEATH] — once per summoned entity when it dies / despawns.
 *
 * Wave _skills-2 / ADR-0077.
 */
public enum class SummonPhase { CAST, SPAWN, SUMMON_DEATH }
