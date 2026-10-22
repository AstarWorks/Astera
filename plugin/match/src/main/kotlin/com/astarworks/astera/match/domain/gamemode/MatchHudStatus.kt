package com.astarworks.astera.match.domain.gamemode

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.TeamId

/**
 * One viewer-neutral sidebar line (WP14, ADR-0177): an i18n key plus its
 * placeholders. When [team] is set the HUD service adds the viewer-localised
 * `{team}` name and the `{color}` MiniMessage colour name, so a mode never
 * renders strings itself.
 */
public data class HudLine(
    public val key: MessageKey,
    public val placeholders: Map<String, String> = emptyMap(),
    public val team: TeamId? = null,
)

/**
 * What a game mode wants shown on the match sidebar, between the generic
 * header and the viewer's own footer.
 *
 * @property phaseLine replaces the generic "phase + elapsed time" line when the
 *   mode has its own clock (Annihilation: time to the next Nexus phase).
 * @property lines objective state, one line per team (or per fact), top to bottom.
 */
public data class MatchHudStatus(
    public val phaseLine: HudLine? = null,
    public val lines: List<HudLine> = emptyList(),
) {
    public companion object {
        /** One `team: score` line per team, straight from the match score. */
        public fun scoreLines(match: Match): List<HudLine> = match.teams.map { team ->
            HudLine(
                key = MessageKey("astera.hud.sidebar.score"),
                placeholders = mapOf("score" to match.score.valueFor(team.id).toString()),
                team = team.id,
            )
        }
    }
}
