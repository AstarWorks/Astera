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
import com.astarworks.astera.match.application.port.outbound.ObjectiveLook
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.mode.GameModeId
import kotlin.math.abs
import kotlin.math.min

/**
 * Carry Mini TNT: small TNT pieces sit on the stage's pickup spots (`pickup_regions`). Standing on a spot for
 * `channel_ticks` picks a piece up (up to `carry_limit` at a time); every piece slows and lights up the carrier a
 * little more. Holding an ENEMY `base-<team>` zone for `channel_ticks` plants everything carried there. A taken piece
 * grows back after `respawn_seconds`; a carrier who falls drops what they carried in a pile where they died, anyone can
 * collect it, and it burns out after `drop_return_seconds`. Every planted piece takes `count_per_plant` off the
 * team's count (count model, see [SiegeGameMode]). Entering your own base plants nothing.
 */
public class CarryMiniTntGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CarryMiniTntGameMode.Run>(ID, TITLE_KEY, env) {

    override val fastTickTicks: Long = FAST_TICKS

    /** Pieces lying where a carrier fell. */
    public class Pile(public val id: Int, public val position: Vec3, public var count: Int, public val tick: Long)

    public class Run(
        matchId: MatchId,
        stage: Stage,
        public val spots: Map<String, Vec3>,
        public val spotRegions: Map<String, Region>,
        public val bases: Map<TeamId, Region>,
    ) : SiegeRun(matchId, stage) {
        /** Spot region -> server tick it was taken at; absent = the piece is lying there. */
        public val takenAt: MutableMap<String, Long> = mutableMapOf()
        public val carried: MutableMap<PlayerId, Int> = mutableMapOf()

        public val piles: MutableList<Pile> = mutableListOf()
        internal var nextPileId: Int = 1

        /** Last place each carrier was seen alive. */
        internal val lastPos: MutableMap<PlayerId, Vec3> = mutableMapOf()
    }

    private fun carryLimit(): Int = env?.int("carry_limit", DEFAULT_CARRY_LIMIT) ?: DEFAULT_CARRY_LIMIT

    private fun respawnTicks(): Long = (env?.int("respawn_seconds", DEFAULT_RESPAWN_SECONDS) ?: DEFAULT_RESPAWN_SECONDS) * 20L

    override fun createRun(match: Match, stage: Stage): Run? {
        val bases = match.teams.associate { it.id to (stage.region("$BASE_PREFIX${it.id.value}") ?: return null) }
        val names = env?.string("pickup_regions", DEFAULT_PICKUPS) ?: DEFAULT_PICKUPS
        val regions = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { name -> stage.region(name)?.let { name to it } }
            .toMap()
        if (regions.isEmpty()) return null
        return Run(match.id, stage, regions.mapValues { cellOf(it.value) }, regions, bases)
    }

    override fun start(run: Run, match: Match) {
        for ((name, cell) in run.spots) {
            setBlock(run, cell, MARKER_BLOCK)
            showSpot(run, match, name)
        }
    }

    // ---- fast loop ----------------------------------------------------------

    override fun fastTick(run: Run, match: Match) {
        if (run.ended) return
        val now = env?.let { it.now() } ?: return
        trackCarriers(run, match, now)
        expirePiles(run, match, now)
        for (team in match.teams) {
            for (member in team.members) {
                if (run.ended) return
                val target = kit.positionOf(member)?.let { targetOf(run, member, team.id, it) }
                if (kit.channel(member, target, if (target?.startsWith(PLANT) == true) LABEL_PLANT else LABEL_PICKUP)) {
                    complete(run, match, member, team.id, target ?: continue, now)
                }
            }
        }
    }

    private fun trackCarriers(run: Run, match: Match, now: Long) {
        for (carrier in run.carried.keys.toList()) {
            val pos = kit.positionOf(carrier)
            if (pos == null) {
                // Offline without a death event: the pieces fall where the carrier was last seen.
                dropPile(run, match, carrier, run.lastPos[carrier], now)
                continue
            }
            run.lastPos[carrier] = pos
            val team = teamOf(match, carrier) ?: continue
            if (run.due("trail-${carrier.uuid}", now, TRAIL_TICKS)) kit.trail(run.stage.world, pos, ObjectiveKit.colorOf(team))
        }
    }

    private fun expirePiles(run: Run, match: Match, now: Long) {
        val gone = run.piles.filter { now - it.tick >= kit.dropReturnTicks }
        for (pile in gone) removePile(run, match, pile)
    }

    private fun targetOf(run: Run, player: PlayerId, team: TeamId, pos: Vec3): String? {
        val have = run.carried[player] ?: 0
        if (have > 0) {
            run.bases.entries.firstOrNull { (owner, region) -> owner != team && region.contains(pos) }?.let { return "$PLANT${it.key.value}" }
        }
        if (have >= carryLimit()) return null
        run.spotRegions.entries.firstOrNull { (name, region) -> name !in run.takenAt && region.contains(pos) }?.let { return "$SPOT${it.key}" }
        val pile = run.piles.firstOrNull { ObjectiveKit.flatDistance(pos, it.position) <= kit.pickupRadius && abs(pos.y - it.position.y) <= REACH_HEIGHT }
        return pile?.let { "$PILE${it.id}" }
    }

    private fun complete(run: Run, match: Match, player: PlayerId, team: TeamId, target: String, now: Long) {
        when {
            target.startsWith(SPOT) -> collectSpot(run, match, player, target.removePrefix(SPOT), now)
            target.startsWith(PILE) -> collectPile(run, match, player, target.removePrefix(PILE).toIntOrNull())
            target.startsWith(PLANT) -> run.bases.entries.firstOrNull { it.key.value == target.removePrefix(PLANT) }
                ?.takeIf { it.key != team }?.let { plant(run, match, player, team, it.key, it.value) }
        }
    }

    private fun collectSpot(run: Run, match: Match, player: PlayerId, region: String, now: Long) {
        val cell = run.spots[region] ?: return
        val have = run.carried[player] ?: 0
        if (region in run.takenAt || have >= carryLimit()) return
        run.takenAt[region] = now
        setBlock(run, cell, floorBlock())
        kit.unmarker(match.id, "$SPOT_KEY$region")
        gain(run, match, player, 1)
    }

    private fun collectPile(run: Run, match: Match, player: PlayerId, id: Int?) {
        val pile = run.piles.firstOrNull { it.id == id } ?: return
        val take = min(pile.count, carryLimit() - (run.carried[player] ?: 0))
        if (take <= 0) return
        pile.count -= take
        if (pile.count <= 0) removePile(run, match, pile)
        gain(run, match, player, take)
    }

    private fun gain(run: Run, match: Match, player: PlayerId, pieces: Int) {
        val have = (run.carried[player] ?: 0) + pieces
        run.carried[player] = have
        run.lastPos[player] = kit.positionOf(player) ?: run.lastPos[player] ?: return
        kit.carry(player, have, LOOK)
        tellPlayer(player, KEY_PICKED_UP, mapOf("count" to have.toString(), "limit" to carryLimit().toString()))
        kit.broadcast(listOf(player), PresentationMoments.OBJECTIVE_COLLECT, mapOf("count" to have.toString(), "limit" to carryLimit().toString()), run.lastPos[player], run.stage.world)
    }

    /** Enemy base: the pieces the player carries are planted there; enough of them blow the base up. */
    private fun plant(run: Run, match: Match, player: PlayerId, team: TeamId, enemy: TeamId, base: Region) {
        val have = run.carried.remove(player) ?: return
        run.lastPos.remove(player)
        kit.uncarry(player)
        val left = (countOf(run, team) - countPer(EVENT_PLANT) * have).coerceAtLeast(0)
        val args = mapOf("team" to team.value, "enemy" to enemy.value, "pieces" to have.toString(), "count" to left.toString(), "per" to countPer(EVENT_PLANT).toString())
        tellMatch(match, KEY_PLANTED, args)
        val burst = ObjectiveKit.centreOf(base, 1.0)
        val ours = match.teams.firstOrNull { it.id == team }?.members.orEmpty()
        kit.broadcast(ours, PresentationMoments.OBJECTIVE_DELIVER, args, burst, run.stage.world)
        kit.broadcast(match.teams.filter { it.id != team }.flatMap { it.members }, PresentationMoments.OBJECTIVE_DELIVER_AGAINST, args, burst, run.stage.world)
        countEvent(run, match, team, EVENT_PLANT, times = have, at = burst)
        if (run.ended) tellMatch(match, KEY_BLOWN_UP, mapOf("team" to team.value, "enemy" to enemy.value))
    }

    // ---- drops ----------------------------------------------------------------

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        if (victim !in run.carried) return
        val now = env?.let { it.now() } ?: return
        dropPile(run, match, victim, kit.positionOf(victim, includeDead = true) ?: run.lastPos[victim], now)
    }

    private fun dropPile(run: Run, match: Match, victim: PlayerId, at: Vec3?, now: Long) {
        val count = run.carried.remove(victim) ?: return
        run.lastPos.remove(victim)
        kit.uncarry(victim)
        val landing = at?.takeIf { run.bases.values.minOf { it.min.y } - VOID_MARGIN < it.y }
        tellPlayer(victim, KEY_DROPPED)
        if (landing == null) return
        val pile = Pile(run.nextPileId++, landing, count, now)
        run.piles += pile
        kit.marker(match.id, "$PILE_KEY${pile.id}", run.stage.world, landing, LOOK)
        kit.broadcast(match.teams.flatMap { it.members }, PresentationMoments.OBJECTIVE_DROP, at = landing, world = run.stage.world)
    }

    private fun removePile(run: Run, match: Match, pile: Pile) {
        run.piles.remove(pile)
        kit.unmarker(match.id, "$PILE_KEY${pile.id}")
    }

    private fun showSpot(run: Run, match: Match, name: String) {
        val region = run.spotRegions[name] ?: return
        kit.marker(match.id, "$SPOT_KEY$name", run.stage.world, ObjectiveKit.centreOf(region, 1.0), LOOK)
    }

    // ---- slow loop --------------------------------------------------------------

    override fun tickRun(run: Run, match: Match, tick: Long) {
        val now = env?.let { it.now() } ?: return
        val grown = run.takenAt.filterValues { now - it >= respawnTicks() }.keys
        for (region in grown) {
            run.takenAt.remove(region)
            run.spots[region]?.let { setBlock(run, it, MARKER_BLOCK) }
            showSpot(run, match, region)
        }
        val world = run.stage.world
        for (pile in run.piles) kit.beam(world, pile.position, null)
        for ((owner, region) in run.bases) kit.beam(world, ObjectiveKit.centreOf(region), ObjectiveKit.colorOf(owner))
        for (team in match.teams) {
            for (member in team.members) {
                statusBar(run, member, team.id)
                compass(run, member, team.id)
            }
        }
        warnDefenders(run, match, now)
    }

    private fun statusBar(run: Run, viewer: PlayerId, team: TeamId) {
        val left = countOf(run, team)
        kit.statusBar(
            viewer, BAR,
            mapOf("count" to (run.carried[viewer] ?: 0).toString(), "limit" to carryLimit().toString(), "left" to left.toString()),
            1.0 - left.toDouble() / countStart(), ObjectiveKit.barColorOf(ObjectiveKit.colorOf(team)),
        )
    }

    private fun compass(run: Run, viewer: PlayerId, team: TeamId) {
        val from = kit.positionOf(viewer) ?: return
        val have = run.carried[viewer] ?: 0
        if (have > 0) {
            val goal = run.bases.entries.filter { it.key != team }.minByOrNull { ObjectiveKit.flatDistance(from, ObjectiveKit.centreOf(it.value)) } ?: return
            kit.compass(viewer, ObjectiveKit.centreOf(goal.value), HINT_PLANT, mapOf("team" to goal.key.value))
            return
        }
        val free = run.spotRegions.filterKeys { it !in run.takenAt }.values.map { ObjectiveKit.centreOf(it) } + run.piles.map { it.position }
        val nearest = free.minByOrNull { ObjectiveKit.flatDistance(from, it) } ?: return
        kit.compass(viewer, nearest, HINT_GRAB)
    }

    /** An enemy carrier close to a base: its owners are warned once every two seconds. */
    private fun warnDefenders(run: Run, match: Match, now: Long) {
        if (!run.due("warn", now, WARN_INTERVAL_TICKS)) return
        for ((carrier, _) in run.carried) {
            val team = teamOf(match, carrier) ?: continue
            val pos = kit.positionOf(carrier) ?: continue
            for ((owner, region) in run.bases) {
                if (owner == team || ObjectiveKit.flatDistance(pos, ObjectiveKit.centreOf(region)) > kit.warnDistance) continue
                kit.broadcast(match.teams.firstOrNull { it.id == owner }?.members.orEmpty(), PresentationMoments.OBJECTIVE_WARN, at = pos, world = run.stage.world)
            }
        }
    }

    override fun holdsObjective(run: Run, match: Match, team: TeamId): Boolean =
        run.carried.any { (carrier, pieces) -> pieces > 0 && teamOf(match, carrier) == team }

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val loose = HudLine(
            MessageKey("astera.hud.sidebar.carry-mini-tnt.loose"),
            placeholders = mapOf("count" to (run.spots.size - run.takenAt.size + run.piles.sumOf { it.count }).toString()),
        )
        return listOf(loose)
    }

    override fun finish(run: Run) {
        for (cell in run.spots.values) setBlock(run, cell, floorBlock())
    }

    public companion object {
        public val ID: GameModeId = GameModeId("carry-mini-tnt")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.title")
        private val KEY_PICKED_UP: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.picked-up")
        private val KEY_PLANTED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.planted")
        private val KEY_BLOWN_UP: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.blown-up")
        private val KEY_DROPPED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.dropped")
        private val LABEL_PICKUP: MessageKey = MessageKey("astera.objective.channel.carry-mini-tnt.pickup")
        private val LABEL_PLANT: MessageKey = MessageKey("astera.objective.channel.carry-mini-tnt.plant")
        private val BAR: MessageKey = MessageKey("astera.objective.bar.carry-mini-tnt")
        private val HINT_PLANT: MessageKey = MessageKey("astera.objective.hint.carry-mini-tnt.plant")
        private val HINT_GRAB: MessageKey = MessageKey("astera.objective.hint.carry-mini-tnt.grab")
        private val LOOK: ObjectiveLook = ObjectiveLook("minecraft:tnt", "red", 0.5)
        private const val BASE_PREFIX: String = "base-"
        private const val MARKER_BLOCK: String = "minecraft:tnt"
        private const val SPOT: String = "spot:"
        private const val PILE: String = "pile:"
        private const val PLANT: String = "plant:"
        private const val SPOT_KEY: String = "spot-"
        private const val PILE_KEY: String = "pile-"
        private const val DEFAULT_PICKUPS: String = "wool-a,wool-b,wool-c"
        private const val EVENT_PLANT: String = "plant"
        private const val DEFAULT_CARRY_LIMIT: Int = 4
        private const val DEFAULT_RESPAWN_SECONDS: Int = 15
        private const val FAST_TICKS: Long = 5L
        private const val TRAIL_TICKS: Long = 5L
        private const val WARN_INTERVAL_TICKS: Long = 40L
        private const val REACH_HEIGHT: Double = 3.0
        private const val VOID_MARGIN: Double = 8.0
    }
}
