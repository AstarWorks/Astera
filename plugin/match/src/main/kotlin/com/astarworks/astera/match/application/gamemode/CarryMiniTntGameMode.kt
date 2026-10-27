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
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveSnapshot
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.application.port.outbound.ObjectiveLook
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.mode.GameModeId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Carry Mini TNT (Clam Blitz / ガチアサリ): minis ("clams") sit on the stage's `clam-<n>` spots and grow back after
 * `respawn_seconds`. Standing on a spot for `channel_ticks` picks one up; a player carries up to `carry_limit`. When the
 * clams in a team's hands add up to `power_need` the collector gets a power TNT (the clams are used up).
 *
 * Every enemy `basket-<team>` has a barrier that only a thrown-in power TNT breaks: holding the basket with a power TNT
 * throws it in (`count_per_power`) and opens the barrier for `barrier_open_seconds`; while it is open, carried minis
 * can be planted there for `count_per_mini` each. A carrier who falls scatters what they carried into piles, anyone
 * can collect them, and they burn out after `drop_return_seconds`. Overtime runs while the trailing team holds a
 * power TNT. See [SiegeGameMode] for the count model.
 */
public class CarryMiniTntGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<CarryMiniTntGameMode.Run>(ID, TITLE_KEY, env) {

    override val fastTickTicks: Long = FAST_TICKS

    /** Pieces lying where a carrier fell; a [power] pile holds one power TNT. */
    public class Pile(public val id: Int, public val position: Vec3, public var count: Int, public val tick: Long, public val power: Boolean = false)

    public class Run(
        matchId: MatchId,
        stage: Stage,
        public val spots: Map<String, Vec3>,
        public val spotRegions: Map<String, Region>,
        public val baskets: Map<TeamId, Region>,
    ) : SiegeRun(matchId, stage) {
        /** Spot region -> server tick it was taken at; absent = the piece is lying there. */
        public val takenAt: MutableMap<String, Long> = mutableMapOf()
        public val carried: MutableMap<PlayerId, Int> = mutableMapOf()

        /** Power TNTs held per player. */
        public val power: MutableMap<PlayerId, Int> = mutableMapOf()

        /** Basket owner -> server tick the barrier closes again; absent = closed. */
        public val openUntil: MutableMap<TeamId, Long> = mutableMapOf()

        public val piles: MutableList<Pile> = mutableListOf()
        internal var nextPileId: Int = 1

        /** Last place each carrier was seen alive. */
        internal val lastPos: MutableMap<PlayerId, Vec3> = mutableMapOf()

        public fun open(team: TeamId, now: Long): Boolean = (openUntil[team] ?: 0L) > now
    }

    private fun carryLimit(): Int = env?.int("carry_limit", DEFAULT_CARRY_LIMIT) ?: DEFAULT_CARRY_LIMIT

    private fun powerNeed(): Int = env?.int("power_need", DEFAULT_POWER_NEED) ?: DEFAULT_POWER_NEED

    private fun respawnTicks(): Long = (env?.int("respawn_seconds", DEFAULT_RESPAWN_SECONDS) ?: DEFAULT_RESPAWN_SECONDS) * 20L

    private fun barrierTicks(): Long = (env?.int("barrier_open_seconds", DEFAULT_BARRIER_SECONDS) ?: DEFAULT_BARRIER_SECONDS) * 20L

    private fun scatterPiles(): Int = env?.int("scatter_piles", DEFAULT_SCATTER) ?: DEFAULT_SCATTER

    private fun scatterRadius(): Double = (env?.int("scatter_radius", DEFAULT_SCATTER_RADIUS) ?: DEFAULT_SCATTER_RADIUS).toDouble()

    private fun powerWeight(): Int = env?.int("power_carry_weight", DEFAULT_POWER_WEIGHT) ?: DEFAULT_POWER_WEIGHT

    override fun createRun(match: Match, stage: Stage): Run? {
        val clams = stage.regionsWithPrefix(CLAM_PREFIX)
        val missing = buildList {
            if (clams.isEmpty()) add("${CLAM_PREFIX}<n>")
            for (team in match.teams) if (stage.region("$BASKET_PREFIX${team.id.value}") == null) add("$BASKET_PREFIX${team.id.value}")
        }
        if (missing.isNotEmpty()) return missingRegions(stage, missing)
        val baskets = match.teams.associate { it.id to stage.region("$BASKET_PREFIX${it.id.value}")!! }
        val regions = clams.associateBy { it.name!! }
        return Run(match.id, stage, regions.mapValues { cellOf(it.value) }, regions, baskets)
    }

    override fun start(run: Run, match: Match) {
        for ((name, cell) in run.spots) {
            setBlock(run, cell, MARKER_BLOCK)
            showSpot(run, match, name)
        }
        for (team in run.baskets.keys) showBasket(run, match, team, 0L)
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
                val target = kit.positionOf(member)?.let { targetOf(run, member, team.id, it, now) }
                if (kit.channel(member, target, labelOf(target))) complete(run, match, member, team.id, target ?: continue, now)
            }
        }
    }

    private fun labelOf(target: String?): MessageKey = when {
        target?.startsWith(THROW) == true -> LABEL_THROW
        target?.startsWith(PLANT) == true -> LABEL_PLANT
        else -> LABEL_PICKUP
    }

    private fun trackCarriers(run: Run, match: Match, now: Long) {
        for (carrier in (run.carried.keys + run.power.keys).toSet()) {
            val pos = kit.positionOf(carrier)
            if (pos == null) {
                // Offline without a death event: the pieces fall where the carrier was last seen.
                scatter(run, match, carrier, run.lastPos[carrier], now)
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

    private fun targetOf(run: Run, player: PlayerId, team: TeamId, pos: Vec3, now: Long): String? {
        val enemy = run.baskets.entries.firstOrNull { (owner, region) -> owner != team && region.contains(pos) }
        if (enemy != null) {
            if ((run.power[player] ?: 0) > 0) return "$THROW${enemy.key.value}"
            if ((run.carried[player] ?: 0) > 0 && run.open(enemy.key, now)) return "$PLANT${enemy.key.value}"
        }
        val have = run.carried[player] ?: 0
        if (have >= carryLimit()) return null
        run.spotRegions.entries.firstOrNull { (name, region) -> name !in run.takenAt && region.contains(pos) }?.let { return "$SPOT${it.key}" }
        val pile = run.piles.firstOrNull { ObjectiveKit.flatDistance(pos, it.position) <= kit.pickupRadius && abs(pos.y - it.position.y) <= REACH_HEIGHT }
        return pile?.let { "$PILE${it.id}" }
    }

    private fun complete(run: Run, match: Match, player: PlayerId, team: TeamId, target: String, now: Long) {
        when {
            target.startsWith(SPOT) -> collectSpot(run, match, player, team, target.removePrefix(SPOT), now)
            target.startsWith(PILE) -> collectPile(run, match, player, target.removePrefix(PILE).toIntOrNull())
            target.startsWith(THROW) -> run.baskets.entries.firstOrNull { it.key.value == target.removePrefix(THROW) }
                ?.takeIf { it.key != team }?.let { throwIn(run, match, player, team, it.key, it.value, now) }
            target.startsWith(PLANT) -> run.baskets.entries.firstOrNull { it.key.value == target.removePrefix(PLANT) }
                ?.takeIf { it.key != team }?.let { plant(run, match, player, team, it.key, it.value, now) }
        }
    }

    private fun collectSpot(run: Run, match: Match, player: PlayerId, team: TeamId, region: String, now: Long) {
        val cell = run.spots[region] ?: return
        val have = run.carried[player] ?: 0
        if (region in run.takenAt || have >= carryLimit()) return
        run.takenAt[region] = now
        setBlock(run, cell, floorBlock())
        kit.unmarker(match.id, "$SPOT_KEY$region")
        gain(run, match, player, 1)
        formPower(run, match, player, team)
    }

    private fun collectPile(run: Run, match: Match, player: PlayerId, id: Int?) {
        val pile = run.piles.firstOrNull { it.id == id } ?: return
        if (pile.power) {
            removePile(run, match, pile)
            run.power[player] = (run.power[player] ?: 0) + 1
            run.lastPos[player] = kit.positionOf(player) ?: run.lastPos[player] ?: return
            refreshCarry(run, player)
            return
        }
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
        refreshCarry(run, player)
        tellPlayer(player, KEY_PICKED_UP, mapOf("count" to have.toString(), "limit" to carryLimit().toString()))
        kit.broadcast(listOf(player), PresentationMoments.OBJECTIVE_COLLECT, mapOf("count" to have.toString(), "limit" to carryLimit().toString()), run.lastPos[player], run.stage.world)
    }

    /** Re-applies the carried look and slowdown of [player] from what they hold. */
    private fun refreshCarry(run: Run, player: PlayerId) {
        val pieces = run.carried[player] ?: 0
        val power = run.power[player] ?: 0
        if (pieces <= 0 && power <= 0) {
            kit.uncarry(player)
            return
        }
        kit.carry(player, pieces + power * powerWeight(), if (power > 0) POWER_LOOK else LOOK)
    }

    private fun teamCarried(run: Run, match: Match, team: TeamId): Int =
        match.teams.firstOrNull { it.id == team }?.members.orEmpty().sumOf { run.carried[it] ?: 0 }

    /** The clams in [team]'s hands reach `power_need`: [collector] gets a power TNT, the clams are used up. */
    private fun formPower(run: Run, match: Match, collector: PlayerId, team: TeamId) {
        val need = powerNeed()
        if (teamCarried(run, match, team) < need) return
        var left = need
        val members = match.teams.firstOrNull { it.id == team }?.members.orEmpty()
        for (member in listOf(collector) + members.filter { it != collector }.sortedByDescending { run.carried[it] ?: 0 }) {
            if (left <= 0) break
            val have = run.carried[member] ?: continue
            val take = min(have, left)
            left -= take
            if (have - take <= 0) run.carried.remove(member) else run.carried[member] = have - take
            if (member != collector) refreshCarry(run, member)
        }
        run.power[collector] = (run.power[collector] ?: 0) + 1
        run.lastPos[collector] = kit.positionOf(collector) ?: run.lastPos[collector] ?: return
        refreshCarry(run, collector)
        tellMatch(match, KEY_POWER, mapOf("team" to team.value))
        kit.burst(run.stage.world, run.lastPos.getValue(collector), ObjectiveKit.colorOf(team))
        kit.broadcast(members, PresentationMoments.OBJECTIVE_PICKUP, at = run.lastPos[collector], world = run.stage.world)
    }

    /** Enemy basket with a power TNT: it is thrown in, the barrier opens and the throw counts. */
    private fun throwIn(run: Run, match: Match, player: PlayerId, team: TeamId, enemy: TeamId, basket: Region, now: Long) {
        val have = run.power[player] ?: return
        if (have <= 1) run.power.remove(player) else run.power[player] = have - 1
        refreshCarry(run, player)
        run.openUntil[enemy] = now + barrierTicks()
        showBasket(run, match, enemy, now)
        val args = mapOf("team" to team.value, "enemy" to enemy.value, "seconds" to (barrierTicks() / 20L).toString())
        tellMatch(match, KEY_THROWN, args)
        val burst = ObjectiveKit.centreOf(basket, 1.0)
        kit.burst(run.stage.world, burst, ObjectiveKit.colorOf(team))
        val ours = match.teams.firstOrNull { it.id == team }?.members.orEmpty()
        kit.broadcast(ours, PresentationMoments.OBJECTIVE_DELIVER, args, burst, run.stage.world)
        kit.broadcast(match.teams.filter { it.id != team }.flatMap { it.members }, PresentationMoments.OBJECTIVE_DELIVER_AGAINST, args, burst, run.stage.world)
        countEvent(run, match, team, EVENT_POWER, at = burst)
        if (run.ended) tellMatch(match, KEY_BLOWN_UP, mapOf("team" to team.value, "enemy" to enemy.value))
    }

    /** Enemy basket with its barrier open: the minis the player carries are planted there. */
    private fun plant(run: Run, match: Match, player: PlayerId, team: TeamId, enemy: TeamId, basket: Region, now: Long) {
        if (!run.open(enemy, now)) return
        val have = run.carried.remove(player) ?: return
        run.lastPos.remove(player)
        refreshCarry(run, player)
        val per = countPer(EVENT_MINI)
        val args = mapOf("team" to team.value, "enemy" to enemy.value, "pieces" to have.toString(), "count" to (countOf(run, team) - per * have).coerceAtLeast(0).toString(), "per" to per.toString())
        tellMatch(match, KEY_PLANTED, args)
        val burst = ObjectiveKit.centreOf(basket, 1.0)
        val ours = match.teams.firstOrNull { it.id == team }?.members.orEmpty()
        kit.broadcast(ours, PresentationMoments.OBJECTIVE_DELIVER, args, burst, run.stage.world)
        kit.broadcast(match.teams.filter { it.id != team }.flatMap { it.members }, PresentationMoments.OBJECTIVE_DELIVER_AGAINST, args, burst, run.stage.world)
        countEvent(run, match, team, EVENT_MINI, times = have, at = burst)
        if (run.ended) tellMatch(match, KEY_BLOWN_UP, mapOf("team" to team.value, "enemy" to enemy.value))
    }

    // ---- drops ----------------------------------------------------------------

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        if (victim !in run.carried && victim !in run.power) return
        val now = env?.let { it.now() } ?: return
        scatter(run, match, victim, kit.positionOf(victim, includeDead = true) ?: run.lastPos[victim], now)
    }

    /** What [victim] carried falls around [at]: minis spread over up to `scatter_piles` piles, a power TNT lies on its own. */
    private fun scatter(run: Run, match: Match, victim: PlayerId, at: Vec3?, now: Long) {
        val count = run.carried.remove(victim) ?: 0
        val power = run.power.remove(victim) ?: 0
        run.lastPos.remove(victim)
        kit.uncarry(victim)
        if (count <= 0 && power <= 0) return
        tellPlayer(victim, KEY_DROPPED)
        val landing = at?.takeIf { run.baskets.values.minOf { it.min.y } - VOID_MARGIN < it.y } ?: return
        repeat(power) { addPile(run, match, landing, 1, now, power = true) }
        if (count > 0) {
            val piles = min(count, scatterPiles())
            for (i in 0 until piles) {
                val share = count / piles + if (i < count % piles) 1 else 0
                val angle = 2 * Math.PI * i / piles
                val at2 = if (piles == 1) landing else Vec3(landing.x + cos(angle) * scatterRadius(), landing.y, landing.z + sin(angle) * scatterRadius())
                addPile(run, match, at2, share, now, power = false)
            }
        }
        kit.broadcast(match.teams.flatMap { it.members }, PresentationMoments.OBJECTIVE_DROP, at = landing, world = run.stage.world)
    }

    private fun addPile(run: Run, match: Match, at: Vec3, count: Int, now: Long, power: Boolean) {
        val pile = Pile(run.nextPileId++, at, count, now, power)
        run.piles += pile
        kit.marker(match.id, "$PILE_KEY${pile.id}", run.stage.world, at, if (power) POWER_LOOK.copy(loose = true) else LOOK.copy(loose = true))
    }

    private fun removePile(run: Run, match: Match, pile: Pile) {
        run.piles.remove(pile)
        kit.unmarker(match.id, "$PILE_KEY${pile.id}")
    }

    private fun showSpot(run: Run, match: Match, name: String) {
        val region = run.spotRegions[name] ?: return
        kit.marker(match.id, "$SPOT_KEY$name", run.stage.world, ObjectiveKit.centreOf(region, 1.0), LOOK.copy(loose = true))
    }

    private fun showBasket(run: Run, match: Match, owner: TeamId, now: Long) {
        val region = run.baskets[owner] ?: return
        val look = if (run.open(owner, now)) {
            val seconds = (((run.openUntil[owner] ?: now) - now + 19) / 20).coerceAtLeast(0L)
            ObjectiveLook("minecraft:lime_stained_glass", "green", 0.9, spin = true, label = "<green><bold>OPEN ${seconds}s</bold></green>")
        } else {
            ObjectiveLook("minecraft:iron_bars", "gray", 0.9, label = "<gray><bold>BARRIER</bold></gray>")
        }
        kit.marker(match.id, "$BASKET_KEY${owner.value}", run.stage.world, ObjectiveKit.centreOf(region, 2.0), look)
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
        for (owner in run.baskets.keys) {
            val was = run.openUntil[owner]
            if (was != null && was <= now) {
                run.openUntil.remove(owner)
                tellMatch(match, KEY_CLOSED, mapOf("enemy" to owner.value))
            }
            showBasket(run, match, owner, now)
        }
        val world = run.stage.world
        for (pile in run.piles) kit.beam(world, pile.position, null)
        for ((owner, region) in run.baskets) kit.beam(world, ObjectiveKit.centreOf(region), if (run.open(owner, now)) ObjectiveKit.colorOf(owner) else null)
        for (team in match.teams) {
            for (member in team.members) {
                statusBar(run, member, team.id)
                compass(run, member, team.id, now)
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

    private fun compass(run: Run, viewer: PlayerId, team: TeamId, now: Long) {
        val from = kit.positionOf(viewer) ?: return
        val enemyBasket = run.baskets.entries.filter { it.key != team }.minByOrNull { ObjectiveKit.flatDistance(from, ObjectiveKit.centreOf(it.value)) }
        if ((run.power[viewer] ?: 0) > 0 && enemyBasket != null) {
            kit.compass(viewer, ObjectiveKit.centreOf(enemyBasket.value), HINT_THROW, mapOf("team" to enemyBasket.key.value))
            return
        }
        if ((run.carried[viewer] ?: 0) > 0 && enemyBasket != null && run.open(enemyBasket.key, now)) {
            kit.compass(viewer, ObjectiveKit.centreOf(enemyBasket.value), HINT_PLANT, mapOf("team" to enemyBasket.key.value))
            return
        }
        val free = run.spotRegions.filterKeys { it !in run.takenAt }.values.map { ObjectiveKit.centreOf(it) } + run.piles.map { it.position }
        val nearest = free.minByOrNull { ObjectiveKit.flatDistance(from, it) } ?: return
        kit.compass(viewer, nearest, HINT_GRAB)
    }

    /** An enemy carrier close to a basket: its owners are warned once every two seconds. */
    private fun warnDefenders(run: Run, match: Match, now: Long) {
        if (!run.due("warn", now, WARN_INTERVAL_TICKS)) return
        for (carrier in (run.carried.keys + run.power.keys).toSet()) {
            val team = teamOf(match, carrier) ?: continue
            val pos = kit.positionOf(carrier) ?: continue
            for ((owner, region) in run.baskets) {
                if (owner == team || ObjectiveKit.flatDistance(pos, ObjectiveKit.centreOf(region)) > kit.warnDistance) continue
                kit.broadcast(match.teams.firstOrNull { it.id == owner }?.members.orEmpty(), PresentationMoments.OBJECTIVE_WARN, at = pos, world = run.stage.world)
            }
        }
    }

    override fun holdsObjective(run: Run, match: Match, team: TeamId): Boolean =
        run.power.any { (carrier, n) -> n > 0 && teamOf(match, carrier) == team }

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val loose = HudLine(
            MessageKey("astera.hud.sidebar.carry-mini-tnt.loose"),
            placeholders = mapOf("count" to (run.spots.size - run.takenAt.size + run.piles.filter { !it.power }.sumOf { it.count }).toString()),
        )
        val banks = match.teams.map { team ->
            HudLine(
                MessageKey("astera.hud.sidebar.carry-mini-tnt.bank"),
                mapOf("have" to teamCarried(run, match, team.id).toString(), "need" to powerNeed().toString()),
                team.id,
            )
        }
        return listOf(loose) + banks
    }

    override fun snapshotOf(run: Run, match: Match): ObjectiveSnapshot {
        val now = env?.now() ?: 0L
        return ObjectiveSnapshot.Clams(
            freeClams = run.spotRegions.filterKeys { it !in run.takenAt }.mapValues { ObjectiveKit.centreOf(it.value, 1.0) },
            piles = run.piles.filter { !it.power }.map { it.position to it.count },
            baskets = run.baskets.mapValues { (owner, region) ->
                ObjectiveSnapshot.Clams.Basket(ObjectiveKit.centreOf(region), run.open(owner, now), (((run.openUntil[owner] ?: now) - now + 19) / 20).toInt().coerceAtLeast(0))
            },
            carried = run.carried.toMap(),
            powerHolders = run.power.filterValues { it > 0 }.keys,
        )
    }

    override fun finish(run: Run) {
        for (cell in run.spots.values) setBlock(run, cell, floorBlock())
    }

    public companion object {
        public val ID: GameModeId = GameModeId("carry-mini-tnt")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.title")
        private val KEY_PICKED_UP: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.picked-up")
        private val KEY_PLANTED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.planted")
        private val KEY_THROWN: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.thrown")
        private val KEY_POWER: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.power")
        private val KEY_CLOSED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.closed")
        private val KEY_BLOWN_UP: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.blown-up")
        private val KEY_DROPPED: MessageKey = MessageKey("astera.gamemode.carry-mini-tnt.dropped")
        private val LABEL_PICKUP: MessageKey = MessageKey("astera.objective.channel.carry-mini-tnt.pickup")
        private val LABEL_PLANT: MessageKey = MessageKey("astera.objective.channel.carry-mini-tnt.plant")
        private val LABEL_THROW: MessageKey = MessageKey("astera.objective.channel.carry-mini-tnt.throw")
        private val BAR: MessageKey = MessageKey("astera.objective.bar.carry-mini-tnt")
        private val HINT_PLANT: MessageKey = MessageKey("astera.objective.hint.carry-mini-tnt.plant")
        private val HINT_THROW: MessageKey = MessageKey("astera.objective.hint.carry-mini-tnt.throw")
        private val HINT_GRAB: MessageKey = MessageKey("astera.objective.hint.carry-mini-tnt.grab")
        private val LOOK: ObjectiveLook = ObjectiveLook("minecraft:tnt", "red", 0.5)
        private val POWER_LOOK: ObjectiveLook = ObjectiveLook("minecraft:gold_block", "gold", 1.0, spin = true)
        private const val CLAM_PREFIX: String = "clam-"
        private const val BASKET_PREFIX: String = "basket-"
        private const val MARKER_BLOCK: String = "minecraft:tnt"
        private const val SPOT: String = "spot:"
        private const val PILE: String = "pile:"
        private const val PLANT: String = "plant:"
        private const val THROW: String = "throw:"
        private const val SPOT_KEY: String = "spot-"
        private const val PILE_KEY: String = "pile-"
        private const val BASKET_KEY: String = "basket-"
        private const val EVENT_MINI: String = "mini"
        private const val EVENT_POWER: String = "power"
        private const val DEFAULT_CARRY_LIMIT: Int = 8
        private const val DEFAULT_POWER_NEED: Int = 10
        private const val DEFAULT_RESPAWN_SECONDS: Int = 20
        private const val DEFAULT_BARRIER_SECONDS: Int = 10
        private const val DEFAULT_SCATTER: Int = 6
        private const val DEFAULT_SCATTER_RADIUS: Int = 2
        private const val DEFAULT_POWER_WEIGHT: Int = 3
        private const val FAST_TICKS: Long = 5L
        private const val TRAIL_TICKS: Long = 5L
        private const val WARN_INTERVAL_TICKS: Long = 40L
        private const val REACH_HEIGHT: Double = 3.0
        private const val VOID_MARGIN: Double = 8.0
    }
}
