package com.astarworks.astera.match.application.gamemode

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
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveSnapshot
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.application.port.outbound.ObjectiveBarColor
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.mode.GameModeId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Capture Wool (Splat Zones / ガチエリア): the stage's central `wool-*` zones (one or two) are 5x5 floor pads under a
 * tall beam. A team alone in a zone fills a progress ring (`capture_seconds` for a neutral zone, `recapture_seconds`
 * for one an enemy holds); a contested zone pauses and an abandoned one drains.
 *
 * Count: while one team owns EVERY zone its count falls by `count_per_second` each second; split ownership of two zones
 * pauses both counts. Retaking a zone makes the new owner burn a penalty first: `retake_penalty_pct` percent of the
 * seconds the previous owner counted down during its hold (at most `penalty_cap_seconds`), shown as "+N penalty".
 * Overtime runs while the trailing team owns every zone.
 */
public class CaptureWoolGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CaptureWoolGameMode.Run>(ID, TITLE_KEY, env) {

    /** One zone: [owner] holds it; [challenger] has filled [seconds] of the (re)capture. */
    public class ZoneState(public var owner: TeamId? = null, public var challenger: TeamId? = null, public var seconds: Double = 0.0)

    public class Run(matchId: MatchId, stage: Stage, public val markers: Map<String, Vec3>) : SiegeRun(matchId, stage) {
        public val zones: Map<String, ZoneState> = markers.keys.associateWith { ZoneState() }
        public val occupants: MutableMap<String, MutableMap<PlayerId, TeamId>> = mutableMapOf()

        /** Seconds of count each team earned during its current hold; the taker of its zone pays a share. */
        internal val gained: MutableMap<TeamId, Int> = mutableMapOf()

        /** Seconds a team still has to burn before its count moves again. */
        public val penalty: MutableMap<TeamId, Int> = mutableMapOf()
        internal val barred: MutableSet<PlayerId> = mutableSetOf()

        public fun held(team: TeamId): Int = zones.values.count { it.owner == team }

        /** The team that owns every zone, or `null`. */
        public fun sole(): TeamId? = zones.values.map { it.owner }.distinct().singleOrNull()
    }

    private fun captureSeconds(): Int = env?.int("capture_seconds", 10) ?: 10

    private fun recaptureSeconds(): Int = env?.int("recapture_seconds", 8) ?: 8

    private fun decaySeconds(): Double = (env?.int("progress_decay_per_second", 1) ?: 1).toDouble()

    private fun countPerSecond(): Int = env?.int("count_per_second", 1) ?: 1

    private fun retakePenaltyPct(): Int = env?.int("retake_penalty_pct", 50) ?: 50

    private fun penaltyCapSeconds(): Int = env?.int("penalty_cap_seconds", 20) ?: 20

    private fun floorRadius(): Int = env?.int("floor_radius", 2) ?: 2

    private fun innerRadius(): Int = env?.int("inner_radius", 1) ?: 1

    private fun beamHeight(): Int = env?.int("beam_height", 20) ?: 20

    override fun createRun(match: Match, stage: Stage): Run? {
        val zones = stage.regionsWithPrefix(ZONE_PREFIX)
        if (zones.isEmpty()) return missingRegions(stage, listOf("${ZONE_PREFIX}<x>"))
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
            if (run.ended) return
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

    override fun navTarget(run: Run, match: Match, viewer: PlayerId, team: TeamId): NavTarget? =
        kit.nearest(viewer, run.markers.filterKeys { run.zones.getValue(it).owner != team }.values)?.let { NavTarget(it, HINT_ZONE) }

    private fun needFor(state: ZoneState, challenger: TeamId?): Int =
        if (state.owner != null && challenger != state.owner) recaptureSeconds() else captureSeconds()

    private fun capture(run: Run, match: Match, zone: String, cell: Vec3, centre: Vec3, winner: TeamId) {
        val state = run.zones.getValue(zone)
        val previous = state.owner
        state.owner = winner
        state.challenger = null
        state.seconds = 0.0
        val world = run.stage.world
        kit.burst(world, centre, ObjectiveKit.colorOf(winner))
        kit.broadcast(match.teams.flatMap { it.members }.filter { teamOf(match, it) == winner }, PresentationMoments.OBJECTIVE_CAPTURE, at = centre, world = world)
        paint(run, cell, winner)
        tellMatch(match, KEY_CAPTURED, mapOf("team" to winner.value, "zone" to zone))
        if (previous != null && previous != winner) {
            val owed = (run.gained[previous] ?: 0) * retakePenaltyPct() / 100
            run.gained[previous] = 0
            val penalty = owed.coerceAtMost(penaltyCapSeconds())
            run.penalty[winner] = penalty
            if (penalty > 0) tellMatch(match, KEY_PENALTY, mapOf("team" to winner.value, "seconds" to penalty.toString()))
        }
        announceObjectiveShift(run, match, winner, centre)
    }

    /** Once a second: the team that owns every zone burns its penalty first, then counts down. */
    private fun scoreHeld(run: Run, match: Match) {
        if (run.ended) return
        val team = run.sole() ?: return
        val owing = run.penalty[team] ?: 0
        if (owing > 0) {
            run.penalty[team] = owing - 1
            for (member in match.teams.firstOrNull { it.id == team }?.members.orEmpty()) {
                kit.toast(member, TOAST_PENALTY, mapOf("seconds" to (owing - 1).toString()), ttlTicks = 30)
            }
            return
        }
        run.gained[team] = (run.gained[team] ?: 0) + 1
        val zone = run.markers.values.first()
        countDown(run, match, team, countPerSecond(), at = Vec3(zone.x + 0.5, zone.y + 1.0, zone.z + 0.5))
    }

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

    /** The trailing team keeps the match alive in overtime while it owns every zone (that is when its count moves). */
    override fun holdsObjective(run: Run, match: Match, team: TeamId): Boolean = run.sole() == team

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val open = run.zones.values.count { it.owner == null }
        val lines = listOf(HudLine(MessageKey("astera.hud.sidebar.capture-wool.open"), mapOf("open" to open.toString(), "total" to run.markers.size.toString())))
        val owing = run.penalty.entries.firstOrNull { it.value > 0 } ?: return lines
        return lines + HudLine(MessageKey("astera.hud.sidebar.capture-wool.penalty"), mapOf("seconds" to owing.value.toString()), team = owing.key)
    }

    override fun snapshotOf(run: Run, match: Match): ObjectiveSnapshot =
        ObjectiveSnapshot.Zones(
            zones = run.markers.mapValues { (name, cell) ->
                val state = run.zones.getValue(name)
                val need = needFor(state, state.challenger).toDouble()
                ObjectiveSnapshot.Zones.Zone(Vec3(cell.x + 0.5, cell.y + 1.0, cell.z + 0.5), state.owner, state.challenger, if (state.challenger != null) state.seconds / need else 0.0)
            },
            penaltySeconds = run.penalty.filterValues { it > 0 },
        )

    override fun finish(run: Run) {
        val r = floorRadius()
        for (cell in run.markers.values) for (dx in -r..r) for (dz in -r..r) setBlock(run, Vec3(cell.x + dx, cell.y, cell.z + dz), floorBlock())
    }

    public companion object {
        public val ID: GameModeId = GameModeId("capture-wool")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.capture-wool.title")
        private val KEY_CAPTURED: MessageKey = MessageKey("astera.gamemode.capture-wool.captured")
        private val KEY_PENALTY: MessageKey = MessageKey("astera.gamemode.capture-wool.penalty")
        private val BAR_CAPTURING: MessageKey = MessageKey("astera.objective.bar.capture-wool.capturing")
        private val BAR_RECAPTURING: MessageKey = MessageKey("astera.objective.bar.capture-wool.recapturing")
        private val BAR_CONTESTED: MessageKey = MessageKey("astera.objective.bar.capture-wool.contested")
        private val HINT_ZONE: MessageKey = MessageKey("astera.objective.hint.capture-wool.zone")
        private val TOAST_PROGRESS: MessageKey = MessageKey("astera.objective.toast.capture-wool.progress")
        private val TOAST_PENALTY: MessageKey = MessageKey("astera.objective.toast.capture-wool.penalty")
        private const val ZONE_PREFIX: String = "wool-"
        private const val NEUTRAL_WOOL: String = "minecraft:white_wool"
        private const val NEUTRAL_CONCRETE: String = "minecraft:light_gray_concrete"
        private const val AIR: String = "minecraft:air"
        private val COLORS: Set<String> = setOf("red", "blue", "yellow", "green", "orange", "purple", "pink", "lime", "cyan")
    }
}
