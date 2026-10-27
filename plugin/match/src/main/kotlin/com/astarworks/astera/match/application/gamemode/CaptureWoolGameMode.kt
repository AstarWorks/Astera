package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.application.gamemode.siege.NavTarget
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveKit
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.application.port.outbound.ObjectiveBarColor
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.match.domain.mode.RegionCaptureProgress

/**
 * Capture Wool: the stage's `wool-*` zones are control points, each marked by a
 * white wool block. A team that is alone in a zone for `capture_seconds`
 * captures it (the marker turns into that team's wool and the zone stays
 * captured); a contested or empty zone resets. Each capture takes `count_per_capture` off the team's count
 * (count model, see [SiegeGameMode]).
 */
public class CaptureWoolGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CaptureWoolGameMode.Run>(ID, TITLE_KEY, env) {

    public class Run(matchId: MatchId, stage: Stage, public val markers: Map<String, Vec3>) : SiegeRun(matchId, stage) {
        public val progress: MutableMap<String, RegionCaptureProgress> =
            markers.keys.associateWith { RegionCaptureProgress.empty(it) }.toMutableMap()
        public val occupants: MutableMap<String, MutableMap<PlayerId, TeamId>> = mutableMapOf()
        internal val owner: MutableMap<String, TeamId> = mutableMapOf()
        internal val barred: MutableSet<PlayerId> = mutableSetOf()
    }

    private fun captureSeconds(): Long = (env?.int("capture_seconds", DEFAULT_CAPTURE_SECONDS) ?: DEFAULT_CAPTURE_SECONDS).toLong()

    override fun createRun(match: Match, stage: Stage): Run? {
        val zones = stage.regionsWithPrefix(ZONE_PREFIX)
        if (zones.isEmpty()) return null
        return Run(match.id, stage, zones.associate { it.name!! to cellOf(it) })
    }

    override fun start(run: Run, match: Match) {
        run.markers.values.forEach { setBlock(run, it, NEUTRAL_WOOL) }
    }

    override fun entered(run: Run, match: Match, player: PlayerId, team: TeamId, region: String) {
        if (region in run.markers) run.occupants.getOrPut(region) { mutableMapOf() }[player] = team
    }

    override fun left(run: Run, player: PlayerId, region: String) {
        run.occupants[region]?.remove(player)
    }

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        run.occupants.values.forEach { it.remove(victim) }
    }

    override fun tickRun(run: Run, match: Match, tick: Long) {
        val world = run.stage.world
        val barNow = mutableSetOf<PlayerId>()
        for ((zone, cell) in run.markers) {
            val current = run.progress.getValue(zone)
            val above = Vec3(cell.x + 0.5, cell.y + 1.0, cell.z + 0.5)
            run.owner[zone]?.let { kit.beam(world, above, ObjectiveKit.colorOf(it)) }
            if (current.capturedBy != null) continue
            val occupants = run.occupants[zone].orEmpty()
            val teams = occupants.values.toSet()
            val advanced = current.withTick(teams.singleOrNull(), captureSeconds())
            run.progress[zone] = advanced
            if (teams.size > 1) {
                kit.beam(world, above, "red")
                for (p in occupants.keys) { barNow += p; showCapture(p, BAR_CONTESTED, zone, 0.0, ObjectiveBarColor.RED) }
            } else if (teams.size == 1 && advanced.capturedBy == null) {
                val team = teams.single()
                val color = ObjectiveKit.colorOf(team)
                kit.beam(world, above, color)
                val progress = advanced.accumulatedTicks.toDouble() / captureSeconds()
                for (p in occupants.keys) { barNow += p; showCapture(p, BAR_CAPTURING, zone, progress, ObjectiveBarColor.GREEN, advanced.accumulatedTicks) }
            }
            val winner = advanced.capturedBy ?: continue
            run.owner[zone] = winner
            kit.burst(world, above, ObjectiveKit.colorOf(winner))
            kit.broadcast(match.teams.flatMap { it.members }.filter { teamOf(match, it) == winner }, PresentationMoments.OBJECTIVE_CAPTURE, at = above, world = world)
            setBlock(run, cell, woolOf(winner))
            tellMatch(match, KEY_CAPTURED, mapOf("team" to winner.value, "zone" to zone, "count" to (countOf(run, winner) - countPer(EVENT_CAPTURE)).coerceAtLeast(0).toString()))
            countEvent(run, match, winner, EVENT_CAPTURE, at = above)
            if (run.ended) return
        }
        for (gone in run.barred - barNow) kit.hideStatusBar(gone)
        run.barred.clear()
        run.barred += barNow
    }

    private fun showCapture(player: PlayerId, key: MessageKey, zone: String, progress: Double, color: ObjectiveBarColor, seconds: Long = 0L) {
        kit.statusBar(player, key, mapOf("zone" to zone, "seconds" to seconds.toString(), "need" to captureSeconds().toString()), progress, color)
    }

    override fun navTarget(run: Run, match: Match, viewer: PlayerId, team: TeamId): NavTarget? =
        kit.nearest(viewer, run.markers.filterKeys { run.owner[it] != team }.values)?.let { NavTarget(it, HINT_ZONE) }

    override fun holdsObjective(run: Run, match: Match, team: TeamId): Boolean =
        run.progress.any { (zone, progress) -> progress.capturedBy == null && team in run.occupants[zone].orEmpty().values }

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val open = run.progress.values.count { it.capturedBy == null }
        return listOf(HudLine(MessageKey("astera.hud.sidebar.capture-wool.open"), mapOf("open" to open.toString(), "total" to run.markers.size.toString())))
    }

    override fun finish(run: Run) {
        run.markers.values.forEach { setBlock(run, it, floorBlock()) }
    }

    private fun woolOf(team: TeamId): String =
        if (team.value in WOOL_COLORS) "minecraft:${team.value}_wool" else NEUTRAL_WOOL

    public companion object {
        private const val EVENT_CAPTURE: String = "capture"
        public val ID: GameModeId = GameModeId("capture-wool")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.capture-wool.title")
        private val KEY_CAPTURED: MessageKey = MessageKey("astera.gamemode.capture-wool.captured")
        private val BAR_CAPTURING: MessageKey = MessageKey("astera.objective.bar.capture-wool.capturing")
        private val BAR_CONTESTED: MessageKey = MessageKey("astera.objective.bar.capture-wool.contested")
        private val HINT_ZONE: MessageKey = MessageKey("astera.objective.hint.capture-wool.zone")
        private const val ZONE_PREFIX: String = "wool-"
        private const val NEUTRAL_WOOL: String = "minecraft:white_wool"
        private const val DEFAULT_CAPTURE_SECONDS: Int = 10
        private val WOOL_COLORS: Set<String> = setOf("red", "blue", "yellow", "green", "orange", "purple", "pink", "lime", "cyan")
    }
}
