package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Resolves a team's MiniMessage colour name (`astera.match.team.<id>.color`) for HUD
 * text. One definition so the kill feed, tab list and nameplates agree on what
 * "red team" looks like; unknown values fall back to white.
 */
public object TeamColors {

    public const val FALLBACK: String = "white"

    /** MiniMessage named colours a team colour key may resolve to. */
    public val NAMED: Set<String> = setOf(
        "red", "blue", "yellow", "green", "gold", "aqua", "white", "gray",
        "dark_red", "dark_blue", "dark_green", "dark_aqua", "dark_purple", "light_purple",
    )

    public fun nameOf(team: Team?, viewer: PlayerId?, messages: IMessageRenderer): String =
        team?.let { messages.render(viewer, it.colorKey) }?.takeIf { it in NAMED } ?: FALLBACK
}
