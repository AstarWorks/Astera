package com.astarworks.astera.core.domain.skill.phase

/**
 * Lifecycle phases of a teleport-archetype skill.
 *
 *  - [CAST] — at cast time, before any movement.
 *  - [PRE_TELEPORT] — just before the position change (caster still at origin).
 *  - [POST_TELEPORT] — just after the position change (caster at destination).
 *
 * Wave _skills-2 / ADR-0077.
 */
public enum class TeleportPhase { CAST, PRE_TELEPORT, POST_TELEPORT }
