package com.astarworks.astera.match.domain.norma

/** What the match sidebar shows one player: stars carried, Norma level and the current goal's progress. */
public data class NormaHud(
    val stars: Int,
    val level: Int,
    val maxLevel: Int,
    val goal: NormaGoal,
    val progress: Int,
    val target: Int,
    /** The best Norma level held by any other team (the race that wins the match); `null` when there is no rival. */
    val rivalLevel: Int? = null,
)
