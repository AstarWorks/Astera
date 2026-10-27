package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveKit
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.application.gamemode.siege.TntCarrier
import com.astarworks.astera.match.application.port.outbound.ObjectiveBarColor
import com.astarworks.astera.match.application.port.outbound.ObjectiveLook
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.mode.GameModeId
import kotlin.math.abs

/**
 * Carry TNT (Rainmaker-style): one TNT sits in the `tnt-spawn` zone. Standing in the zone for `channel_ticks` picks it
 * up (only while nobody carries it); the carrier is slowed, glows and wears the TNT; holding an enemy `base-<team>`
 * zone for `channel_ticks` scores a delivery for the carrier's team. A carrier who falls drops the TNT where they
 * died: anyone can pick it up there, and it goes back to the spawn after `drop_return_seconds`. First team to
 * `deliveries_to_win` deliveries wins.
 */
public class CarryTntGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CarryTntGameMode.Run>(ID, TITLE_KEY, env) {

    override val fastTickTicks: Long = FAST_TICKS

    public class Run(
        matchId: MatchId,
        stage: Stage,
        public val spawnCell: Vec3,
        public val spawn: Region,
        public val bases: Map<TeamId, Region>,
    ) : SiegeRun(matchId, stage) {
        public val tnt: TntCarrier = TntCarrier()
        public val deliveries: MutableMap<TeamId, Int> = mutableMapOf()

        /** Last place the carrier was seen alive (where the TNT lands if the carrier vanishes). */
        public var lastCarrierPos: Vec3? = null

        /** Whether the TNT block still stands on the spawn pedestal. */
        public var pedestalLit: Boolean = true
    }

    private fun deliveriesToWin(): Int = env?.int("deliveries_to_win", DEFAULT_DELIVERIES) ?: DEFAULT_DELIVERIES

    override fun createRun(match: Match, stage: Stage): Run? {
        val spawn = stage.region(SPAWN_REGION) ?: return null
        val bases = match.teams.associate { it.id to (stage.region("$BASE_PREFIX${it.id.value}") ?: return null) }
        return Run(match.id, stage, cellOf(spawn), spawn, bases)
    }

    override fun start(run: Run, match: Match) {
        setBlock(run, run.spawnCell, MARKER_BLOCK)
        refreshMarker(run, match)
    }

    // ---- fast loop: carrier upkeep, drop timer, channels -----------------------

    override fun fastTick(run: Run, match: Match) {
        if (run.ended) return
        val e = env ?: return
        val now = e.now()
        val carrier = run.tnt.carrier
        if (carrier != null) {
            val pos = kit.positionOf(carrier)
            val team = teamOf(match, carrier)
            if (pos == null) {
                dropCarrier(run, match, carrier, run.lastCarrierPos)
            } else {
                run.lastCarrierPos = pos
                if (team != null && run.due("trail", now, TRAIL_TICKS)) kit.trail(run.stage.world, pos, ObjectiveKit.colorOf(team))
            }
        } else if (run.tnt.droppedAt != null && now - run.tnt.droppedTick >= kit.dropReturnTicks) {
            returnHome(run, match)
        }
        for (team in match.teams) {
            for (member in team.members) {
                if (run.ended) return
                val target = kit.positionOf(member)?.let { targetOf(run, member, team.id, it) }
                if (kit.channel(member, target, labelOf(target))) complete(run, match, member, team.id, target ?: continue)
            }
        }
    }

    private fun targetOf(run: Run, player: PlayerId, team: TeamId, pos: Vec3): String? {
        val tnt = run.tnt
        if (tnt.carrier == player) {
            return run.bases.entries.firstOrNull { (owner, region) -> owner != team && region.contains(pos) }?.let { "$DELIVER_PREFIX${it.key.value}" }
        }
        if (tnt.carrier != null) return null
        val dropped = tnt.droppedAt
        val inReach = if (dropped != null) {
            ObjectiveKit.flatDistance(pos, dropped) <= kit.pickupRadius && abs(pos.y - dropped.y) <= REACH_HEIGHT
        } else {
            run.spawn.contains(pos)
        }
        return if (inReach) PICKUP else null
    }

    private fun labelOf(target: String?): MessageKey = if (target == PICKUP) LABEL_PICKUP else LABEL_DELIVER

    private fun complete(run: Run, match: Match, player: PlayerId, team: TeamId, target: String) {
        if (target == PICKUP) pickedUp(run, match, player, team) else delivered(run, match, player, team, target.removePrefix(DELIVER_PREFIX))
    }

    private fun pickedUp(run: Run, match: Match, player: PlayerId, team: TeamId) {
        if (!run.tnt.pickUp(player)) return
        kit.carry(player, 1, LOOK)
        run.lastCarrierPos = kit.positionOf(player)
        refreshMarker(run, match)
        tellMatch(match, KEY_PICKED_UP, mapOf("team" to team.value))
        val at = run.lastCarrierPos
        kit.broadcast(listOf(player), PresentationMoments.OBJECTIVE_PICKUP, at = at, world = run.stage.world)
        val enemies = match.teams.filter { it.id != team }.flatMap { it.members }
        kit.broadcast(enemies, PresentationMoments.OBJECTIVE_ALERT, at = at, world = run.stage.world)
    }

    private fun delivered(run: Run, match: Match, player: PlayerId, team: TeamId, baseTeam: String) {
        if (!run.tnt.isCarrying(player)) return
        val base = run.bases.entries.firstOrNull { it.key.value == baseTeam }?.takeIf { it.key != team } ?: return
        kit.uncarry(player)
        run.tnt.reset()
        val score = (run.deliveries[team] ?: 0) + 1
        run.deliveries[team] = score
        refreshMarker(run, match)
        tellMatch(match, KEY_DELIVERED, mapOf("team" to team.value, "score" to score.toString(), "goal" to deliveriesToWin().toString()))
        val args = mapOf("team" to team.value, "score" to score.toString(), "goal" to deliveriesToWin().toString())
        val burst = ObjectiveKit.centreOf(base.value, 1.0)
        val ours = match.teams.firstOrNull { it.id == team }?.members.orEmpty()
        val theirs = match.teams.filter { it.id != team }.flatMap { it.members }
        kit.broadcast(ours, PresentationMoments.OBJECTIVE_DELIVER, args, burst, run.stage.world)
        kit.broadcast(theirs, PresentationMoments.OBJECTIVE_DELIVER_AGAINST, args, burst, run.stage.world)
        if (score >= deliveriesToWin()) win(run, team)
    }

    // ---- drop / return ----------------------------------------------------

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        if (!run.tnt.isCarrying(victim)) return
        dropCarrier(run, match, victim, kit.positionOf(victim, includeDead = true) ?: run.lastCarrierPos)
    }

    /** The carrier [victim] is gone: the TNT lies at [at] (back at the spawn when that is over the void / unknown). */
    private fun dropCarrier(run: Run, match: Match, victim: PlayerId, at: Vec3?) {
        val now = env?.let { it.now() } ?: return
        kit.uncarry(victim)
        val landing = at?.takeIf { it.y > run.spawn.min.y - VOID_MARGIN }
        if (landing == null) {
            if (run.tnt.isCarrying(victim)) returnHome(run, match)
            return
        }
        if (!run.tnt.drop(victim, landing, now)) return
        refreshMarker(run, match)
        tellMatch(match, KEY_DROPPED, mapOf("team" to (teamOf(match, victim)?.value ?: "")))
        val everyone = match.teams.flatMap { it.members }
        kit.broadcast(everyone, PresentationMoments.OBJECTIVE_DROP, at = landing, world = run.stage.world)
    }

    private fun returnHome(run: Run, match: Match) {
        run.tnt.reset()
        refreshMarker(run, match)
        tellMatch(match, KEY_RETURNED)
        val everyone = match.teams.flatMap { it.members }
        kit.broadcast(everyone, PresentationMoments.OBJECTIVE_RETURN, at = ObjectiveKit.centreOf(run.spawn, 1.0), world = run.stage.world)
    }

    /** Where the TNT lies right now (dropped spot or spawn centre); `null` while carried. */
    private fun loosePos(run: Run): Vec3? =
        if (run.tnt.carrier != null) null else run.tnt.droppedAt ?: ObjectiveKit.centreOf(run.spawn, 1.0)

    private fun refreshMarker(run: Run, match: Match) {
        val pos = loosePos(run)
        if (pos == null) kit.unmarker(match.id, MARKER_KEY) else kit.marker(match.id, MARKER_KEY, run.stage.world, pos, LOOK)
        val atHome = pos != null && run.tnt.droppedAt == null
        if (atHome != run.pedestalLit) {
            run.pedestalLit = atHome
            setBlock(run, run.spawnCell, if (atHome) MARKER_BLOCK else floorBlock())
        }
    }

    // ---- slow loop: beams, HUD bars, compass, warnings --------------------------

    override fun tickRun(run: Run, match: Match, tick: Long) {
        val now = env?.let { it.now() } ?: return
        val world = run.stage.world
        val loose = loosePos(run)
        if (loose != null) kit.beam(world, loose, null)
        for ((owner, region) in run.bases) kit.beam(world, ObjectiveKit.centreOf(region), ObjectiveKit.colorOf(owner))
        refreshMarker(run, match)
        val carrier = run.tnt.carrier
        val carrierTeam = carrier?.let { teamOf(match, it) }
        for (team in match.teams) {
            for (member in team.members) {
                statusBar(run, member, carrierTeam, now)
                compass(run, match, member, team.id, carrier, carrierTeam, loose)
            }
        }
        warnDefenders(run, match, carrier, carrierTeam, now)
    }

    private fun statusBar(run: Run, viewer: PlayerId, carrierTeam: TeamId?, now: Long) {
        val tnt = run.tnt
        when {
            carrierTeam != null ->
                kit.statusBar(viewer, BAR_CARRIED, mapOf("team" to carrierTeam.value), 1.0, ObjectiveKit.barColorOf(ObjectiveKit.colorOf(carrierTeam)))
            tnt.droppedAt != null -> {
                val left = (kit.dropReturnTicks - (now - tnt.droppedTick)).coerceAtLeast(0L)
                kit.statusBar(viewer, BAR_DROPPED, mapOf("seconds" to ((left + 19) / 20).toString()), left.toDouble() / kit.dropReturnTicks, ObjectiveBarColor.YELLOW)
            }
            else -> kit.statusBar(viewer, BAR_LOOSE, emptyMap(), 1.0, ObjectiveBarColor.WHITE)
        }
    }

    private fun compass(run: Run, match: Match, viewer: PlayerId, team: TeamId, carrier: PlayerId?, carrierTeam: TeamId?, loose: Vec3?) {
        when {
            carrier == viewer -> {
                val from = kit.positionOf(viewer) ?: return
                val goal = run.bases.entries.filter { it.key != team }.minByOrNull { ObjectiveKit.flatDistance(from, ObjectiveKit.centreOf(it.value)) } ?: return
                kit.compass(viewer, ObjectiveKit.centreOf(goal.value), HINT_DELIVER, mapOf("team" to goal.key.value))
            }
            carrier != null -> {
                val pos = kit.positionOf(carrier) ?: return
                kit.compass(viewer, pos, if (carrierTeam == team) HINT_ESCORT else HINT_STOP)
            }
            loose != null -> kit.compass(viewer, loose, HINT_GRAB)
        }
    }

    /** The carrier is close to a base: its owners are told once every two seconds. */
    private fun warnDefenders(run: Run, match: Match, carrier: PlayerId?, carrierTeam: TeamId?, now: Long) {
        if (carrier == null || carrierTeam == null || !run.due("warn", now, WARN_INTERVAL_TICKS)) return
        val pos = kit.positionOf(carrier) ?: return
        for ((owner, region) in run.bases) {
            if (owner == carrierTeam || ObjectiveKit.flatDistance(pos, ObjectiveKit.centreOf(region)) > kit.warnDistance) continue
            val defenders = match.teams.firstOrNull { it.id == owner }?.members.orEmpty()
            kit.broadcast(defenders, PresentationMoments.OBJECTIVE_WARN, at = pos, world = run.stage.world)
        }
    }

    override fun standing(run: Run, match: Match, team: TeamId): Int = run.deliveries[team] ?: 0

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val carrierTeam = run.tnt.carrier?.let { c -> match.teams.firstOrNull { c in it.members }?.id }
        val teamLines = match.teams.map { team ->
            HudLine(
                key = MessageKey("astera.hud.sidebar.carry-tnt.team"),
                placeholders = mapOf("score" to (run.deliveries[team.id] ?: 0).toString(), "goal" to deliveriesToWin().toString()),
                team = team.id,
            )
        }
        val carrier = if (carrierTeam != null) {
            HudLine(MessageKey("astera.hud.sidebar.carry-tnt.carried"), team = carrierTeam)
        } else {
            HudLine(MessageKey("astera.hud.sidebar.carry-tnt.loose"))
        }
        return teamLines + carrier
    }

    override fun finish(run: Run) {
        setBlock(run, run.spawnCell, floorBlock())
    }

    public companion object {
        public val ID: GameModeId = GameModeId("carry-tnt")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.carry-tnt.title")
        private val KEY_PICKED_UP: MessageKey = MessageKey("astera.gamemode.carry-tnt.picked-up")
        private val KEY_DELIVERED: MessageKey = MessageKey("astera.gamemode.carry-tnt.delivered")
        private val KEY_DROPPED: MessageKey = MessageKey("astera.gamemode.carry-tnt.dropped")
        private val KEY_RETURNED: MessageKey = MessageKey("astera.gamemode.carry-tnt.returned")
        private val LABEL_PICKUP: MessageKey = MessageKey("astera.objective.channel.carry-tnt.pickup")
        private val LABEL_DELIVER: MessageKey = MessageKey("astera.objective.channel.carry-tnt.deliver")
        private val BAR_LOOSE: MessageKey = MessageKey("astera.objective.bar.carry-tnt.loose")
        private val BAR_CARRIED: MessageKey = MessageKey("astera.objective.bar.carry-tnt.carried")
        private val BAR_DROPPED: MessageKey = MessageKey("astera.objective.bar.carry-tnt.dropped")
        private val HINT_DELIVER: MessageKey = MessageKey("astera.objective.hint.carry-tnt.deliver")
        private val HINT_ESCORT: MessageKey = MessageKey("astera.objective.hint.carry-tnt.escort")
        private val HINT_STOP: MessageKey = MessageKey("astera.objective.hint.carry-tnt.stop")
        private val HINT_GRAB: MessageKey = MessageKey("astera.objective.hint.carry-tnt.grab")
        private val LOOK: ObjectiveLook = ObjectiveLook("minecraft:tnt", "white", 0.8)
        private const val SPAWN_REGION: String = "tnt-spawn"
        private const val BASE_PREFIX: String = "base-"
        private const val MARKER_BLOCK: String = "minecraft:tnt"
        private const val MARKER_KEY: String = "tnt"
        private const val PICKUP: String = "pickup"
        private const val DELIVER_PREFIX: String = "deliver:"
        private const val DEFAULT_DELIVERIES: Int = 3
        private const val FAST_TICKS: Long = 5L
        private const val TRAIL_TICKS: Long = 5L
        private const val WARN_INTERVAL_TICKS: Long = 40L
        private const val REACH_HEIGHT: Double = 3.0
        private const val VOID_MARGIN: Double = 8.0
    }
}
