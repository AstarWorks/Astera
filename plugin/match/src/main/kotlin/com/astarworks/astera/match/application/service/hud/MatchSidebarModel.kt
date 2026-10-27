package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationHudStatus
import com.astarworks.astera.match.domain.norma.NormaGoal
import com.astarworks.astera.match.domain.norma.NormaHud

/**
 * Viewer-neutral content of a match sidebar (WP14, ADR-0177).
 *
 * ```
 * <mode title>
 * Active  3:12          <- phase + elapsed (or the mode's own clock)
 *                       <- spacer
 * <mode lines>          <- per team: score / pillars / objective progress
 *                       <- spacer
 * You: Red   Stars / Lv / goal  <- added per viewer by [MatchSidebarModel.layoutFor]
 * ```
 */
public data class SidebarModel(
    public val titleKey: MessageKey,
    public val lines: List<HudLine>,
)

public object MatchSidebarModel {

    /** Sidebar rows the client shows. */
    private const val MAX_LINES: Int = 15

    /** Lines left for the mode after header (2) and the viewer footer (spacer, team, stars, level, goal = 5). */
    public const val MAX_MODE_LINES: Int = MAX_LINES - 2 - 5

    private val SPACER: MessageKey = MessageKey("astera.hud.sidebar.spacer")

    /** The viewer-independent part, built from the mode's own [IGameMode.hudStatus]. */
    public fun build(mode: IGameMode, match: Match, nowTick: Long, timeLimitTicks: Long = 0L): SidebarModel {
        val status = mode.hudStatus(match, nowTick)
        val header = status.phaseLine ?: genericPhaseLine(match, nowTick, timeLimitTicks)
        val lines = buildList {
            add(header)
            add(HudLine(SPACER))
            addAll(status.lines.take(MAX_MODE_LINES))
        }
        return SidebarModel(titleKey = mode.titleKey(), lines = lines)
    }

    private fun genericPhaseLine(match: Match, nowTick: Long, timeLimitTicks: Long): HudLine {
        val name = match.phase.name.lowercase().replace('_', '-')
        val ticking = match.phase == MatchPhase.ACTIVE || match.phase == MatchPhase.SUDDEN_DEATH
        return if (ticking) {
            val time = AnnihilationHudStatus.clock((nowTick - match.startedAtTick).coerceAtLeast(0))
            // A mode with a time limit shows "3:12 / 20:00" so nobody is surprised by the limit ranking.
            if (match.phase == MatchPhase.ACTIVE && timeLimitTicks > 0) {
                HudLine(MessageKey("astera.hud.sidebar.phase.active-limited"), mapOf("time" to time, "limit" to AnnihilationHudStatus.clock(timeLimitTicks)))
            } else {
                HudLine(MessageKey("astera.hud.sidebar.phase.$name"), mapOf("time" to time))
            }
        } else {
            HudLine(MessageKey("astera.hud.sidebar.phase.$name"))
        }
    }

    /**
     * Resolves [model] for one viewer: team names / colours in the viewer's
     * locale, plus the viewer's own team and star count, Norma level and goal (omitted when
     * [norma] is `null`) as the footer.
     */
    public fun layoutFor(
        model: SidebarModel,
        match: Match,
        viewer: PlayerId,
        messages: IMessageRenderer,
        norma: NormaHud?,
    ): HudLayout.Sidebar {
        val teams = match.teams.associateBy { it.id }
        val footer = buildList {
            add(HudComponent.Text(SPACER))
            match.teams.firstOrNull { viewer in it.members }?.let { own ->
                add(toText(HudLine(MessageKey("astera.hud.sidebar.you"), team = own.id), own, viewer, messages))
            }
            if (norma != null) {
                add(HudComponent.Text(MessageKey("astera.hud.sidebar.stars"), placeholders = mapOf("stars" to norma.stars.toString())))
                val levelParams = mapOf("level" to norma.level.toString(), "max" to norma.maxLevel.toString())
                add(HudComponent.Text(MessageKey("astera.hud.sidebar.level"), placeholders = levelParams))
                if (norma.level >= norma.maxLevel) {
                    add(HudComponent.Text(MessageKey("astera.hud.sidebar.objective-max")))
                } else {
                    val key = if (norma.goal == NormaGoal.STAR) "astera.hud.sidebar.objective-star" else "astera.hud.sidebar.objective-kill"
                    add(HudComponent.Text(MessageKey(key), placeholders = mapOf("progress" to norma.progress.toString(), "target" to norma.target.toString())))
                }
                // What the next level opens for this player (ADR-0273): an ultimate or special they carry.
                norma.nextUnlock?.let {
                    add(
                        HudComponent.Text(
                            MessageKey("astera.hud.sidebar.next-unlock"),
                            placeholders = mapOf("level" to it.level.toString(), "item" to messages.renderText(viewer, it.item)),
                        ),
                    )
                }
            }
        }
        // The footer carries the viewer's Norma goal, so it is never the part that gets cut: the mode body shrinks instead.
        val body = model.lines.take(MAX_LINES - footer.size).map { line -> toText(line, teams[line.team], viewer, messages) }
        return HudLayout.Sidebar(titleKey = model.titleKey, lines = body + footer)
    }

    private fun toText(line: HudLine, team: Team?, viewer: PlayerId, messages: IMessageRenderer): HudComponent.Text {
        if (team == null) return HudComponent.Text(line.key, placeholders = line.placeholders)
        val color = messages.render(viewer, team.colorKey).takeIf { it.isValidColorName() } ?: "white"
        val name = messages.render(viewer, team.displayNameKey)
        return HudComponent.Text(
            messageKey = line.key,
            color = null,
            placeholders = line.placeholders + mapOf("team" to name, "color" to color),
        )
    }

    private fun String.isValidColorName(): Boolean = this in COLORS

    /** MiniMessage named colours a team colour key may resolve to. */
    private val COLORS: Set<String> = setOf(
        "red", "blue", "yellow", "green", "gold", "aqua", "white", "gray",
        "dark_red", "dark_blue", "dark_green", "dark_aqua", "dark_purple", "light_purple",
    )
}
