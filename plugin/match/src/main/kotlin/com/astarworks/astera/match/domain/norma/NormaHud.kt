package com.astarworks.astera.match.domain.norma

/** What the match sidebar shows one player: stars carried, Norma level and the current goal's progress. */
public data class NormaHud(
    val stars: Int,
    val level: Int,
    val maxLevel: Int,
    val goal: NormaGoal,
    val progress: Int,
    val target: Int,
    /** The nearest item action still locked above [level] (the sidebar's "next unlock"); `null` when nothing is left. */
    val nextUnlock: NormaUnlock? = null,
)
