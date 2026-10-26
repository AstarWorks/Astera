package com.astarworks.astera.match.domain.norma

import com.astarworks.astera.core.domain.match.TeamId

/** One team's Norma standing when its match ended: the top level held, stars carried together, kills together. */
public data class NormaStanding(
    val team: TeamId,
    val level: Int,
    val maxLevel: Int,
    val stars: Int,
    val kills: Int,
)
