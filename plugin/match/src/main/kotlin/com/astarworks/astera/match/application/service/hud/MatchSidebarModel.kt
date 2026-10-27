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
 * You: Red   Lv + stars / goal / next unlock  <- added per viewer by [MatchSidebarModel.layoutFor]
 * ```
 */
public data class SidebarModel(
    public val titleKey: MessageKey,
    public val lines: List<HudLine>,
    /** Rows kept after the viewer footer is added; the footer is never cut, the body is. */
    public val maxLines: Int = MatchSidebarModel.MAX_LINES,
)

public object MatchSidebarModel {

    /**
     * Rows kept on the sidebar (the client allows 15, but a player mid-fight reads about seven): header 1, one-line
     * mode objective 1, mode body 2, viewer footer 3 (team, level, goal).
     */
    public const val MAX_LINES: Int = 7

    /** Lines left for the mode after header (1), objective (1) and the viewer footer (3). */
    public const val MAX_MODE_LINES: Int = MAX_LINES - 1 - 1 - 3

    /** Modes that have an `astera.match.objective.<id>.subtitle` one-liner to pin under the phase line. */
    private val OBJECTIVE_MODES: Set<String> = setOf(
        "annihilation", "carry-tnt", "carry-mini-tnt", "capture-wool", "steal-jewelry", "pve-waves",
    )

    /** Footer rows in the worst case: team, level, goal, next unlock. */
    private const val MAX_FOOTER_LINES: Int = 4

    /**
     * Modes whose body must stay whole: Annihilation is played on the enemy pillars' HP, so every team's line (one per
     * team, three in all) has to stay readable instead of being cut to fit [MAX_LINES].
     */
    private val WHOLE_BODY_MODES: Set<String> = setOf("annihilation")

    /** The viewer-independent part, built from the mode's own [IGameMode.hudStatus]. */
    public fun build(mode: IGameMode, match: Match, nowTick: Long, timeLimitTicks: Long = 0L, objectiveParams: Map<String, String> = emptyMap()): SidebarModel {
        val status = mode.hudStatus(match, nowTick)
        val header = status.phaseLine ?: genericPhaseLine(match, nowTick, timeLimitTicks)
        val lines = buildList {
            add(header)
            // Always-visible "what to do now": the mode's one-sentence objective.
            val modeId = mode.id().value
            if (modeId in OBJECTIVE_MODES) add(HudLine(MessageKey("astera.match.objective.$modeId.subtitle"), objectiveParams))
            val keep = if (modeId in WHOLE_BODY_MODES) status.lines.size else if (modeId in OBJECTIVE_MODES) MAX_MODE_LINES else MAX_MODE_LINES + 1
            addAll(status.lines.take(keep))
        }
        val maxLines = if (mode.id().value in WHOLE_BODY_MODES) lines.size + MAX_FOOTER_LINES else MAX_LINES
        return SidebarModel(titleKey = mode.titleKey(), lines = lines, maxLines = maxLines)
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
     * locale, plus the viewer's own team, Norma level with the carried star count, and goal (omitted when
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
            match.teams.firstOrNull { viewer in it.members }?.let { own ->
                add(toText(HudLine(MessageKey("astera.hud.sidebar.you"), team = own.id), own, viewer, messages))
            }
            if (norma != null) {
                // The star count rides on the level row so it stays visible whichever goal (STAR or KILL) the player is on.
                val levelParams = mapOf("level" to norma.level.toString(), "max" to norma.maxLevel.toString(), "stars" to norma.stars.toString())
                add(HudComponent.Text(MessageKey("astera.hud.sidebar.level"), placeholders = levelParams))
                if (norma.level >= norma.maxLevel) {
                    add(HudComponent.Text(MessageKey("astera.hud.sidebar.objective-max")))
                } else {
                    val key = if (norma.goal == NormaGoal.STAR) "astera.hud.sidebar.objective-star" else "astera.hud.sidebar.objective-kill"
                    add(HudComponent.Text(MessageKey(key), placeholders = mapOf("progress" to norma.progress.toString(), "target" to norma.target.toString())))
                }
                // What the next level opens for this player (ADR-0292): the next weapon rarity it opens.
                norma.nextUnlock?.let {
                    add(
                        HudComponent.Text(
                            MessageKey("astera.hud.sidebar.next-unlock"),
                            placeholders = mapOf("level" to it.level.toString(), "rarity" to messages.render(viewer, MessageKey("astera.item.tooltip.rarity.${it.rarity.name.lowercase()}"))),
                        ),
                    )
                }
            }
        }
        // The footer carries the viewer's Norma goal, so it is never the part that gets cut: the mode body shrinks instead.
        val body = model.lines.take(model.maxLines - footer.size).map { line -> toText(line, teams[line.team], viewer, messages) }
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
