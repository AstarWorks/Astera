package com.astarworks.astera.core.domain.skill.phase

/**
 * Lifecycle phases of a projectile-archetype skill.
 *
 *  - [CAST] — at cast time, before the projectile spawns.
 *  - [HIT_ENTITY] — the projectile collided with a living entity.
 *  - [HIT_BLOCK] — the projectile collided with a block.
 *  - [MISS] — the projectile expired without hitting anything.
 *  - [EXPIRE] — fired when the projectile is despawned (after any HIT_* or MISS).
 *
 * Wave _skills-2 / ADR-0077.
 */
public enum class ProjectilePhase { CAST, HIT_ENTITY, HIT_BLOCK, MISS, EXPIRE }
