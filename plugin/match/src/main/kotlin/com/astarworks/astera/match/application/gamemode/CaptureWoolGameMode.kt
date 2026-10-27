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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveKit
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.application.port.outbound.ObjectiveBarColor
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Capture Wool: the stage's `wool-*` zones are control points, each a 5x5 floor pad (outer ring of concrete, inner
 * 3x3 of wool) under a tall beam, grey-white while neutral and in the owner's colour once held. A team alone in a zone
 * fills a progress ring (`capture_seconds` for a neutral zone, `recapture_seconds` for one an enemy holds); a contested
 * zone pauses and an abandoned one drains. A captured zone can be retaken.
 *
 * Holding at least `captures_to_win` zones (default: a majority) for `win_hold_seconds` wins; every second a team holds
 * zones it scores count events ([onObjectiveScored]) of `count_per_second_one` (one zone) or `count_per_second_multi`.
 */
public class CaptureWoolGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CaptureWoolGameMode.Run>(ID, TITLE_KEY, env) {

    /** One zone: [owner] holds it; [challenger] has filled [seconds] of the (re)capture. */
    public class ZoneState(public var owner: TeamId? = null, public var challenger: TeamId? = null, public var seconds: Double = 0.0)

    public class Run(matchId: MatchId, stage: Stage, public val markers: Map<String, Vec3>) : SiegeRun(matchId, stage) {
        public val zones: Map<String, ZoneState> = markers.keys.associateWith { ZoneState() }
        public val occupants: MutableMap<String, MutableMap<PlayerId, TeamId>> = mutableMapOf()

        /** Seconds the current leader has held enough zones in a row. */
        internal var holdTeam: TeamId? = null
        internal var holdSeconds: Int = 0
        internal val barred: MutableSet<PlayerId> = mutableSetOf()

        public fun held(team: TeamId): Int = zones.values.count { it.owner == team }
    }

    private fun captureSeconds(): Int = env?.int("capture_seconds", 10) ?: 10

    private fun recaptureSeconds(): Int = env?.int("recapture_seconds", 8) ?: 8

    private fun decaySeconds(): Double = (env?.int("progress_decay_per_second", 1) ?: 1).toDouble()

    private fun holdToWin(): Int = env?.int("win_hold_seconds", 20) ?: 20

    private fun floorRadius(): Int = env?.int("floor_radius", 2) ?: 2

    private fun innerRadius(): Int = env?.int("inner_radius", 1) ?: 1

    private fun beamHeight(): Int = env?.int("beam_height", 20) ?: 20

    private fun capturesToWin(run: Run): Int =
        env?.config?.get("captures_to_win")?.toIntOrNull() ?: (run.markers.size / 2 + 1)

    override fun createRun(match: Match, stage: Stage): Run? {
        val zones = stage.regionsWithPrefix(ZONE_PREFIX)
        if (zones.isEmpty()) return null
        return Run(match.id, stage, zones.associate { it.name!! to cellOf(it) })
    }

    override fun start(run: Run, match: Match) {
        for (cell in run.markers.values) paint(run, cell, null)
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
            val state = run.zones.getValue(zone)
            val centre = Vec3(cell.x + 0.5, cell.y + 1.0, cell.z + 0.5)
            val occupants = run.occupants[zone].orEmpty()
            val teams = occupants.values.toSet()
            val captured = advance(state, teams)
            val ownerColor = state.owner?.let { ObjectiveKit.colorOf(it) }
            val challenging = state.challenger != null
            kit.beam(world, centre, ownerColor, beamHeight())
            val need = needFor(state, state.challenger).toDouble()
            val fraction = if (challenging) state.seconds / need else if (state.owner != null) 1.0 else 0.0
            kit.ring(world, centre, floorRadius() + 0.5, fraction, state.challenger?.let { ObjectiveKit.colorOf(it) } ?: ownerColor)
            if (teams.size > 1) {
                for (p in occupants.keys) { barNow += p; showBar(p, BAR_CONTESTED, zone, 0.0, ObjectiveBarColor.RED, state, need) }
            } else if (teams.size == 1 && challenging) {
                val team = teams.single()
                val key = if (state.owner != null) BAR_RECAPTURING else BAR_CAPTURING
                for (p in occupants.keys) {
                    barNow += p
                    showBar(p, key, zone, state.seconds / need, ObjectiveBarColor.GREEN, state, need)
                    kit.toast(p, TOAST_PROGRESS, mapOf("zone" to zone, "percent" to (state.seconds / need * 100).roundToInt().coerceIn(0, 100).toString(), "tag" to ObjectiveKit.tagOf(ObjectiveKit.colorOf(team))))
                }
            }
            if (captured != null) capture(run, match, zone, cell, centre, captured)
        }
        for (gone in run.barred - barNow) kit.hideStatusBar(gone)
        run.barred.clear()
        run.barred += barNow
        scoreHeld(run, match)
    }

    /** One second of [state] with [teams] standing in it; returns the team that completed a capture. */
    private fun advance(state: ZoneState, teams: Set<TeamId>): TeamId? {
        val lone = teams.singleOrNull()
        when {
            teams.size > 1 -> return null
            lone == null -> {
                if (state.challenger != null) {
                    state.seconds -= decaySeconds()
                    if (state.seconds <= 0.0) { state.seconds = 0.0; state.challenger = null }
                }
                return null
            }
            lone == state.owner -> { state.challenger = null; state.seconds = 0.0; return null }
        }
        if (state.challenger != lone) { state.challenger = lone; state.seconds = 0.0 }
        state.seconds += 1.0
        return if (state.seconds >= needFor(state, lone)) lone else null
    }

    private fun needFor(state: ZoneState, challenger: TeamId?): Int =
        if (state.owner != null && challenger != state.owner) recaptureSeconds() else captureSeconds()

    private fun capture(run: Run, match: Match, zone: String, cell: Vec3, centre: Vec3, winner: TeamId) {
        val state = run.zones.getValue(zone)
        state.owner = winner
        state.challenger = null
        state.seconds = 0.0
        val world = run.stage.world
        kit.burst(world, centre, ObjectiveKit.colorOf(winner))
        kit.broadcast(match.teams.flatMap { it.members }.filter { teamOf(match, it) == winner }, PresentationMoments.OBJECTIVE_CAPTURE, at = centre, world = world)
        paint(run, cell, winner)
        tellMatch(match, KEY_CAPTURED, mapOf("team" to winner.value, "zone" to zone, "score" to run.held(winner).toString(), "goal" to capturesToWin(run).toString()))
    }

    /** Count events (one call site) and the hold-to-win timer, once a second. */
    private fun scoreHeld(run: Run, match: Match) {
        if (run.ended) return
        var leader: TeamId? = null
        for (team in match.teams) {
            val held = run.held(team.id)
            if (held <= 0) continue
            val perSecond = if (held == 1) env?.int("count_per_second_one", 1) ?: 1 else env?.int("count_per_second_multi", 2) ?: 2
            onObjectiveScored(team.id, perSecond)
            if (held >= capturesToWin(run)) leader = team.id
        }
        if (leader != run.holdTeam) { run.holdTeam = leader; run.holdSeconds = 0 }
        if (leader == null) return
        run.holdSeconds++
        if (run.holdSeconds >= holdToWin()) win(run, leader)
    }

    /** Seam to the shared count model: [team] earned [amount] count from a held zone. The manager wires this at merge. */
    private fun onObjectiveScored(team: TeamId, amount: Int) {}

    private fun showBar(player: PlayerId, key: MessageKey, zone: String, progress: Double, color: ObjectiveBarColor, state: ZoneState, need: Double) {
        kit.statusBar(player, key, mapOf("zone" to zone, "seconds" to ceil(state.seconds).toInt().toString(), "need" to need.toInt().toString()), progress, color)
    }

    /** Lays the 5x5 pad of the zone at [cell]: concrete ring + wool core in [team]'s colour (neutral when null); plants above are cleared. */
    private fun paint(run: Run, cell: Vec3, team: TeamId?) {
        val color = team?.value?.takeIf { it in COLORS }
        val wool = if (color != null) "minecraft:${color}_wool" else NEUTRAL_WOOL
        val concrete = if (color != null) "minecraft:${color}_concrete" else NEUTRAL_CONCRETE
        val r = floorRadius()
        val inner = innerRadius()
        for (dx in -r..r) for (dz in -r..r) {
            val at = Vec3(cell.x + dx, cell.y, cell.z + dz)
            setBlock(run, at, if (abs(dx) <= inner && abs(dz) <= inner) wool else concrete)
            if (team == null) setBlock(run, Vec3(at.x, at.y + 1, at.z), AIR)
        }
    }

    override fun standing(run: Run, match: Match, team: TeamId): Int = run.held(team)

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val goal = capturesToWin(run)
        val teamLines = match.teams.map { team ->
            HudLine(
                key = MessageKey("astera.hud.sidebar.capture-wool.team"),
                placeholders = mapOf("score" to run.held(team.id).toString(), "goal" to goal.toString()),
                team = team.id,
            )
        }
        val open = run.zones.values.count { it.owner == null }
        val lines = teamLines + HudLine(MessageKey("astera.hud.sidebar.capture-wool.open"), mapOf("open" to open.toString(), "total" to run.markers.size.toString()))
        val holder = run.holdTeam ?: return lines
        return lines + HudLine(MessageKey("astera.hud.sidebar.capture-wool.hold"), mapOf("seconds" to (holdToWin() - run.holdSeconds).coerceAtLeast(0).toString()), team = holder)
    }

    override fun finish(run: Run) {
        val r = floorRadius()
        for (cell in run.markers.values) for (dx in -r..r) for (dz in -r..r) setBlock(run, Vec3(cell.x + dx, cell.y, cell.z + dz), floorBlock())
    }

    public companion object {
        public val ID: GameModeId = GameModeId("capture-wool")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.capture-wool.title")
        private val KEY_CAPTURED: MessageKey = MessageKey("astera.gamemode.capture-wool.captured")
        private val BAR_CAPTURING: MessageKey = MessageKey("astera.objective.bar.capture-wool.capturing")
        private val BAR_RECAPTURING: MessageKey = MessageKey("astera.objective.bar.capture-wool.recapturing")
        private val BAR_CONTESTED: MessageKey = MessageKey("astera.objective.bar.capture-wool.contested")
        private val TOAST_PROGRESS: MessageKey = MessageKey("astera.objective.toast.capture-wool.progress")
        private const val ZONE_PREFIX: String = "wool-"
        private const val NEUTRAL_WOOL: String = "minecraft:white_wool"
        private const val NEUTRAL_CONCRETE: String = "minecraft:light_gray_concrete"
        private const val AIR: String = "minecraft:air"
        private val COLORS: Set<String> = setOf("red", "blue", "yellow", "green", "orange", "purple", "pink", "lime", "cyan")
    }
}
