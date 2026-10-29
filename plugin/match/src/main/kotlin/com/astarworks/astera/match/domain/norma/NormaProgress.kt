package com.astarworks.astera.match.domain.norma

/**
 * One player's Norma state inside one match: level (from 1), the goal currently chosen, kills made at this level and
 * all kills made in the match (the last time-limit tie-break).
 */
public data class NormaProgress(
    val level: Int = 1,
    val goal: NormaGoal = NormaGoal.STAR,
    val killsAtLevel: Int = 0,
    val totalKills: Int = 0,
    /** The "ready for the Norma check" notice was already sent for this level (re-armed when a check finds the goal unmet). */
    val readyNotified: Boolean = false,
)
