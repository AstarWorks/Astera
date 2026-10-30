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
 * Viewer-neutral content of a match sidebar (WP14, ADR-0177). The match layout is its own decorated screen,
 * separate from the lobby's account block (no level / XP / gold / achievements here):
 *
 * ```
 * <mode title>
 * Active  3:12          <- phase + elapsed (or the mode's own clock)
 * <objective one-liner> <- OBJECTIVE_MODES only
 * ----------------
 * > Red  12  ...        <- per team: score / pillars / objective (own team marked, bars where a goal exists)
 * ----------------
 * You: Red              <- added per viewer by [MatchSidebarModel.layoutFor]
 * Kills 3 / Deaths 1    <- this match only
 * Norma Lv 2/5  * 3
 * Goal Star 3/10
 * [bar] 30%
 * play.astera.example
 * ```
 */
public data class SidebarModel(
    public val titleKey: MessageKey,
    public val lines: List<HudLine>,
)

public object MatchSidebarModel {

    /** The client draws at most 15 rows; the viewer block and the address are never cut, the mode body is. */
    public const val MAX_LINES: Int = 15

    /** Modes that have an `astera.match.objective.<id>.subtitle` one-liner to pin under the phase line. */
    private val OBJECTIVE_MODES: Set<String> = setOf(
        "annihilation", "carry-tnt", "carry-mini-tnt", "capture-wool", "steal-jewelry", "pve-waves",
    )

    private const val SEPARATOR_KEY: String = "astera.hud.sidebar.separator"

    /** Bar sources inside a mode line: numerator key, denominator key, colour. The first pair present wins. */
    private val BAR_PAIRS: List<Triple<String, String, String>> = listOf(
        Triple("have", "need", "green"),
        Triple("wave", "waves", "gold"),
        Triple("lives", "max", "red"),
    )

    /** The viewer-independent part, built from the mode's own [IGameMode.hudStatus]. */
    public fun build(mode: IGameMode, match: Match, nowTick: Long, timeLimitTicks: Long = 0L, objectiveParams: Map<String, String> = emptyMap()): SidebarModel {
        val status = mode.hudStatus(match, nowTick)
        val header = status.phaseLine ?: genericPhaseLine(match, nowTick, timeLimitTicks)
        val lines = buildList {
            add(header)
            // Always-visible "what to do now": the mode's one-sentence objective.
            val modeId = mode.id().value
            if (modeId in OBJECTIVE_MODES) add(HudLine(MessageKey("astera.match.objective.$modeId.subtitle"), objectiveParams))
            addAll(status.lines)
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
     * Resolves [model] for one viewer: team names / colours in the viewer's locale with the viewer's own team marked,
     * then the viewer block (team, Norma level with the carried star count, goal bar; the goal rows are omitted when
     * [norma] is `null`) and the server address.
     */
    public fun layoutFor(
        model: SidebarModel,
        match: Match,
        viewer: PlayerId,
        messages: IMessageRenderer,
        norma: NormaHud?,
        /** This match's `kills to deaths` of the viewer; `null` hides the K/D row. */
        killDeath: Pair<Int, Int>? = null,
    ): HudLayout.Sidebar {
        val teams = match.teams.associateBy { it.id }
        val own = match.teams.firstOrNull { viewer in it.members }
        val viewerBlock = buildList {
            own?.let { add(toText(HudLine(MessageKey("astera.hud.sidebar.you"), team = it.id), it, own, viewer, messages)) }
            if (own != null && killDeath != null) {
                add(HudComponent.Text(MessageKey("astera.hud.sidebar.kd"), placeholders = mapOf("kills" to killDeath.first.toString(), "deaths" to killDeath.second.toString())))
            }
            if (norma != null) {
                // The star count rides on the level row so it stays visible whichever goal (STAR or KILL) the player is on.
                val levelParams = mapOf("level" to norma.level.toString(), "max" to norma.maxLevel.toString(), "stars" to norma.stars.toString())
                add(HudComponent.Text(MessageKey("astera.hud.sidebar.level"), placeholders = levelParams))
                if (norma.level >= norma.maxLevel) {
                    add(HudComponent.Text(MessageKey("astera.hud.sidebar.objective-max")))
                } else if (norma.ready) {
                    add(HudComponent.Text(MessageKey("astera.hud.sidebar.objective-ready")))
                } else {
                    val star = norma.goal == NormaGoal.STAR
                    val key = if (star) "astera.hud.sidebar.objective-star" else "astera.hud.sidebar.objective-kill"
                    add(HudComponent.Text(MessageKey(key), placeholders = mapOf("progress" to norma.progress.toString(), "target" to norma.target.toString())))
                    add(barText(norma.progress.toLong(), norma.target.toLong(), if (star) "yellow" else "red"))
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
        val address = SidebarBars.footer(viewer, messages)
        val separator = HudComponent.Text(MessageKey(SEPARATOR_KEY))
        // Fixed rows: phase, two separators, the viewer block, the address. The mode body takes what is left.
        val bodyBudget = (MAX_LINES - 1 - 2 - viewerBlock.size - 1).coerceAtLeast(0)
        val phase = model.lines.firstOrNull()?.let { toText(it, teams[it.team], own, viewer, messages) }
        val body = model.lines.drop(1).take(bodyBudget).map { line -> toText(line, teams[line.team], own, viewer, messages) }
        return HudLayout.Sidebar(
            titleKey = model.titleKey,
            lines = listOfNotNull(phase) + separator + body + separator + viewerBlock + address,
        )
    }

    private fun barText(value: Long, max: Long, color: String): HudComponent.Text =
        HudComponent.Text(
            MessageKey("astera.hud.sidebar.bar"),
            placeholders = mapOf(
                "bar" to SidebarBars.bar(value, max, color),
                "percent" to SidebarBars.percent(value, max).toString(),
                "color" to color,
            ),
        )

    /** `have`/`need`-style counters in a mode line become a coloured `bar` placeholder (6 cells, fits next to the text). */
    private fun barPlaceholders(placeholders: Map<String, String>): Map<String, String> {
        for ((num, den, color) in BAR_PAIRS) {
            val n = placeholders[num]?.toLongOrNull() ?: continue
            val d = placeholders[den]?.toLongOrNull() ?: continue
            return mapOf("bar" to SidebarBars.bar(n, d, color, cells = 6))
        }
        return emptyMap()
    }

    private fun toText(line: HudLine, team: Team?, own: Team?, viewer: PlayerId, messages: IMessageRenderer): HudComponent.Text {
        val withBar = line.placeholders + barPlaceholders(line.placeholders)
        if (team == null) return HudComponent.Text(line.key, placeholders = withBar)
        val color = messages.render(viewer, team.colorKey).takeIf { it.isValidColorName() } ?: "white"
        val name = messages.render(viewer, team.displayNameKey)
        // The viewer's own team row starts with a bright marker; the others keep a dim dot so the names stay aligned.
        val mark = if (own != null && own.id == team.id) "<yellow>▶</yellow> " else "<dark_gray>·</dark_gray> "
        return HudComponent.Text(
            messageKey = line.key,
            color = null,
            placeholders = withBar + mapOf("team" to name, "color" to color, "you" to mark),
        )
    }

    private fun String.isValidColorName(): Boolean = this in COLORS

    /** MiniMessage named colours a team colour key may resolve to. */
    private val COLORS: Set<String> = setOf(
        "red", "blue", "yellow", "green", "gold", "aqua", "white", "gray",
        "dark_red", "dark_blue", "dark_green", "dark_aqua", "dark_purple", "light_purple",
    )
}
