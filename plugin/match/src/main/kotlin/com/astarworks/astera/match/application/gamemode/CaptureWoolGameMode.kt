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
 * captured); a contested or empty zone resets. The first team to
 * `captures_to_win` zones wins (default: a majority of the zones).
 */
public class CaptureWoolGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CaptureWoolGameMode.Run>(ID, TITLE_KEY, env) {

    public class Run(matchId: MatchId, stage: Stage, public val markers: Map<String, Vec3>) : SiegeRun(matchId, stage) {
        public val progress: MutableMap<String, RegionCaptureProgress> =
            markers.keys.associateWith { RegionCaptureProgress.empty(it) }.toMutableMap()
        public val occupants: MutableMap<String, MutableMap<PlayerId, TeamId>> = mutableMapOf()
        public val captured: MutableMap<TeamId, Int> = mutableMapOf()
        internal val owner: MutableMap<String, TeamId> = mutableMapOf()
        internal val barred: MutableSet<PlayerId> = mutableSetOf()
    }

    private fun captureSeconds(): Long = (env?.int("capture_seconds", DEFAULT_CAPTURE_SECONDS) ?: DEFAULT_CAPTURE_SECONDS).toLong()

    private fun capturesToWin(run: Run): Int =
        env?.config?.get("captures_to_win")?.toIntOrNull() ?: (run.markers.size / 2 + 1)

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
            val count = (run.captured[winner] ?: 0) + 1
            run.captured[winner] = count
            tellMatch(match, KEY_CAPTURED, mapOf("team" to winner.value, "zone" to zone, "score" to count.toString(), "goal" to capturesToWin(run).toString()))
            if (count >= capturesToWin(run)) {
                win(run, winner)
                return
            }
        }
        for (gone in run.barred - barNow) kit.hideStatusBar(gone)
        run.barred.clear()
        run.barred += barNow
    }

    private fun showCapture(player: PlayerId, key: MessageKey, zone: String, progress: Double, color: ObjectiveBarColor, seconds: Long = 0L) {
        kit.statusBar(player, key, mapOf("zone" to zone, "seconds" to seconds.toString(), "need" to captureSeconds().toString()), progress, color)
    }

    override fun standing(run: Run, match: Match, team: TeamId): Int = run.captured[team] ?: 0

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val goal = capturesToWin(run)
        val teamLines = match.teams.map { team ->
            HudLine(
                key = MessageKey("astera.hud.sidebar.capture-wool.team"),
                placeholders = mapOf("score" to (run.captured[team.id] ?: 0).toString(), "goal" to goal.toString()),
                team = team.id,
            )
        }
        val open = run.progress.values.count { it.capturedBy == null }
        return teamLines + HudLine(MessageKey("astera.hud.sidebar.capture-wool.open"), mapOf("open" to open.toString(), "total" to run.markers.size.toString()))
    }

    override fun finish(run: Run) {
        run.markers.values.forEach { setBlock(run, it, floorBlock()) }
    }

    private fun woolOf(team: TeamId): String =
        if (team.value in WOOL_COLORS) "minecraft:${team.value}_wool" else NEUTRAL_WOOL

    public companion object {
        public val ID: GameModeId = GameModeId("capture-wool")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.capture-wool.title")
        private val KEY_CAPTURED: MessageKey = MessageKey("astera.gamemode.capture-wool.captured")
        private val BAR_CAPTURING: MessageKey = MessageKey("astera.objective.bar.capture-wool.capturing")
        private val BAR_CONTESTED: MessageKey = MessageKey("astera.objective.bar.capture-wool.contested")
        private const val ZONE_PREFIX: String = "wool-"
        private const val NEUTRAL_WOOL: String = "minecraft:white_wool"
        private const val DEFAULT_CAPTURE_SECONDS: Int = 15
        private val WOOL_COLORS: Set<String> = setOf("red", "blue", "yellow", "green", "orange", "purple", "pink", "lime", "cyan")
    }
}
