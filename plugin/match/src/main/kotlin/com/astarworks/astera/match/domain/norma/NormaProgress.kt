package com.astarworks.astera.match.domain.norma

/** One player's Norma state inside one match: level (from 1), the goal currently chosen and kills made at this level. */
public data class NormaProgress(
    val level: Int = 1,
    val goal: NormaGoal = NormaGoal.STAR,
    val killsAtLevel: Int = 0,
)
