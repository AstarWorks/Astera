package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.service.StarterGrantLoader

/** The shipped `content/balance/starter-grant.yaml`, so scenarios derive the starter kit instead of repeating it. */
internal object ShippedStarterGrant {
    private val grant = StarterGrantLoader().load(GameSim.defaultContentDir().resolve("balance/starter-grant.yaml"))

    /** `starter_items`: the weapons a first-time player owns. */
    val starterItems: List<String> = grant.starterItems.map { it.value }

    /** `match_tool_tiers` at Norma level 1: the field tools lent to every participant on match entry, after their own items. */
    val matchTools: List<String> = toolsAt(1)

    /** The field tools lent at Norma [level] (ADR-0319). */
    fun toolsAt(level: Int): List<String> = grant.matchToolsAt(level).map { it.value }
}
