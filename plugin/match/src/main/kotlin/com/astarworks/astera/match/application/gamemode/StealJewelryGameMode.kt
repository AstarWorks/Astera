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

/**
 * Steal Jewelry: each team keeps `gems_per_team` jewels on its pedestal (stage region `jewelry-<team>`). Holding an
 * enemy pedestal zone for `steal_channel_ticks` (damage interrupts) takes one of its jewels; holding your own `base-<team>` zone banks what you
 * carry. Every jewel slows and lights up the carrier a little more. A carrier who falls drops the jewels in a pile
 * where they died: the pile's owners recover their own jewels by touching it, anyone else can steal the rest, and what
 * is left returns to the pedestals after `drop_return_seconds`. Every banked jewel takes `count_per_gem` off the
 * team's count (count model, see [SiegeGameMode]).
 */
public class StealJewelryGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
) : SiegeGameMode<StealJewelryGameMode.Run>(ID, TITLE_KEY, env) {

    override val fastTickTicks: Long = FAST_TICKS

    /** Jewels lying where a carrier fell, tagged with the team each was stolen from. */
    public class Pile(public val id: Int, public val position: Vec3, public val owners: MutableList<TeamId>, public val tick: Long)

    public class Run(
        matchId: MatchId,
        stage: Stage,
        public val pedestals: Map<TeamId, Vec3>,
        public val pedestalRegions: Map<TeamId, Region>,
        public val bases: Map<TeamId, Region>,
        gems: Int,
    ) : SiegeRun(matchId, stage) {
        /** Jewels still on each team's pedestal. */
        public val onPedestal: MutableMap<TeamId, Int> = pedestals.keys.associateWith { gems }.toMutableMap()

        /** Jewels each player carries, tagged with the team they were stolen from. */
        public val carried: MutableMap<PlayerId, MutableList<TeamId>> = mutableMapOf()

        public val piles: MutableList<Pile> = mutableListOf()
        internal var nextPileId: Int = 1
        internal val lastPos: MutableMap<PlayerId, Vec3> = mutableMapOf()
    }

    private fun gemsPerTeam(): Int = env?.int("gems_per_team", DEFAULT_GEMS) ?: DEFAULT_GEMS

    /** Hold time (ticks) to lift a jewel off an enemy pedestal; taking damage interrupts it. */
    private fun stealChannelTicks(): Long = (env?.int("steal_channel_ticks", DEFAULT_STEAL_TICKS) ?: DEFAULT_STEAL_TICKS).toLong()

    override fun damaged(run: Run, match: Match, victim: PlayerId, attacker: PlayerId?, amount: Double) {
        if (amount > 0.0 && kit.isChanneling(victim)) kit.interruptChannel(victim)
    }

    override fun createRun(match: Match, stage: Stage): Run? {
        val regions = match.teams.mapNotNull { team ->
            stage.region("$PEDESTAL_PREFIX${team.id.value}")?.let { team.id to it }
        }.toMap()
        val missing = match.teams.flatMap { listOf("$PEDESTAL_PREFIX${it.id.value}", "$BASE_PREFIX${it.id.value}") }
            .filter { stage.region(it) == null }
        if (missing.isNotEmpty()) return missingRegions(stage, missing)
        val bases = match.teams.associate { it.id to stage.region("$BASE_PREFIX${it.id.value}")!! }
        return Run(match.id, stage, regions.mapValues { cellOf(it.value) }, regions, bases, gemsPerTeam())
    }

    override fun start(run: Run, match: Match) {
        run.pedestals.values.forEach { setBlock(run, it, PEDESTAL_BLOCK) }
        run.pedestalRegions.keys.forEach { showPedestal(run, match, it) }
    }

    // ---- fast loop ----------------------------------------------------------

    override fun fastTick(run: Run, match: Match) {
        if (run.ended) return
        val now = env?.let { it.now() } ?: return
        for (carrier in run.carried.keys.toList()) {
            val pos = kit.positionOf(carrier)
            if (pos == null) {
                dropPile(run, match, carrier, run.lastPos[carrier], now)
                continue
            }
            run.lastPos[carrier] = pos
            val team = teamOf(match, carrier) ?: continue
            if (run.due("trail-${carrier.uuid}", now, TRAIL_TICKS)) kit.trail(run.stage.world, pos, ObjectiveKit.colorOf(team))
        }
        for (pile in run.piles.filter { now - it.tick >= kit.dropReturnTicks }) expirePile(run, match, pile)
        for (team in match.teams) {
            for (member in team.members) {
                if (run.ended) return
                val target = kit.positionOf(member)?.let { targetOf(run, member, team.id, it) }
                val stealing = target?.startsWith(STEAL) == true
                val need = if (stealing) stealChannelTicks() else kit.channelTicks
                if (kit.channel(member, target, if (target?.startsWith(BANK) == true) LABEL_BANK else LABEL_STEAL, needTicks = need)) {
                    complete(run, match, member, team.id, target ?: continue)
                }
            }
        }
    }

    private fun targetOf(run: Run, player: PlayerId, team: TeamId, pos: Vec3): String? {
        if (run.carried[player].orEmpty().isNotEmpty() && run.bases[team]?.contains(pos) == true) return BANK
        run.pedestalRegions.entries
            .firstOrNull { (owner, region) -> owner != team && (run.onPedestal[owner] ?: 0) > 0 && region.contains(pos) }
            ?.let { return "$STEAL${it.key.value}" }
        val pile = run.piles.firstOrNull { ObjectiveKit.flatDistance(pos, it.position) <= kit.pickupRadius && abs(pos.y - it.position.y) <= REACH_HEIGHT }
        return pile?.let { "$PILE${it.id}" }
    }

    private fun complete(run: Run, match: Match, player: PlayerId, team: TeamId, target: String) {
        when {
            target == BANK -> deposit(run, match, player, team)
            target.startsWith(STEAL) -> run.pedestals.keys.firstOrNull { it.value == target.removePrefix(STEAL) }?.let { steal(run, match, player, team, it) }
            target.startsWith(PILE) -> collectPile(run, match, player, team, target.removePrefix(PILE).toIntOrNull())
        }
    }

    private fun steal(run: Run, match: Match, player: PlayerId, team: TeamId, owner: TeamId) {
        if (owner == team || (run.onPedestal[owner] ?: 0) <= 0) return
        run.onPedestal[owner] = run.onPedestal.getValue(owner) - 1
        showPedestal(run, match, owner)
        pickedUp(run, match, player, team, listOf(owner))
        run.pedestalRegions[owner]?.let { kit.burst(run.stage.world, ObjectiveKit.centreOf(it, 1.0), ObjectiveKit.colorOf(owner)) }
        val enemies = match.teams.filter { it.id == owner }.flatMap { it.members }
        kit.broadcast(enemies, PresentationMoments.OBJECTIVE_ALERT, at = run.lastPos[player], world = run.stage.world)
        tellMatch(match, KEY_STOLEN, mapOf("team" to team.value, "victim" to owner.value))
    }

    /** Takes [gems] into the carrier's hands (glow, slowdown, pickup flourish). */
    private fun pickedUp(run: Run, match: Match, player: PlayerId, team: TeamId, gems: List<TeamId>) {
        run.carried.getOrPut(player) { mutableListOf() } += gems
        val pos = kit.positionOf(player) ?: run.lastPos[player]
        if (pos != null) run.lastPos[player] = pos
        kit.carry(player, run.carried.getValue(player).size, LOOK)
        kit.broadcast(listOf(player), PresentationMoments.OBJECTIVE_PICKUP, at = pos, world = run.stage.world)
    }

    private fun collectPile(run: Run, match: Match, player: PlayerId, team: TeamId, id: Int?) {
        val pile = run.piles.firstOrNull { it.id == id } ?: return
        val own = pile.owners.filter { it == team }
        val others = pile.owners.filter { it != team }
        run.piles.remove(pile)
        kit.unmarker(match.id, "$PILE_KEY${pile.id}")
        if (own.isNotEmpty()) {
            own.forEach { run.onPedestal[it] = (run.onPedestal[it] ?: 0) + 1 }
            showPedestal(run, match, team)
            tellMatch(match, KEY_RECOVERED, mapOf("team" to team.value))
            val home = run.pedestalRegions[team]?.let { ObjectiveKit.centreOf(it, 1.0) }
            kit.broadcast(match.teams.flatMap { it.members }, PresentationMoments.OBJECTIVE_RETURN, at = home, world = run.stage.world)
        }
        if (others.isNotEmpty()) pickedUp(run, match, player, team, others)
    }

    private fun deposit(run: Run, match: Match, player: PlayerId, team: TeamId) {
        val gems = run.carried.remove(player)?.takeIf { it.isNotEmpty() } ?: return
        run.lastPos.remove(player)
        kit.uncarry(player)
        val left = (countOf(run, team) - countPer(EVENT_GEM) * gems.size).coerceAtLeast(0)
        val args = mapOf("team" to team.value, "gems" to gems.size.toString(), "count" to left.toString(), "per" to countPer(EVENT_GEM).toString())
        tellMatch(match, KEY_BANKED, args)
        val burst = run.bases[team]?.let { ObjectiveKit.centreOf(it, 1.0) }
        if (burst != null) kit.burst(run.stage.world, burst, ObjectiveKit.colorOf(team))
        kit.broadcast(match.teams.firstOrNull { it.id == team }?.members.orEmpty(), PresentationMoments.OBJECTIVE_DELIVER, args, burst, run.stage.world)
        kit.broadcast(match.teams.filter { it.id != team }.flatMap { it.members }, PresentationMoments.OBJECTIVE_DELIVER_AGAINST, args, burst, run.stage.world)
        countEvent(run, match, team, EVENT_GEM, times = gems.size, at = burst)
        if (run.ended) tellMatch(match, KEY_BANKED, args)
    }

    // ---- drops ----------------------------------------------------------------

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        if (victim !in run.carried) return
        val now = env?.let { it.now() } ?: return
        dropPile(run, match, victim, kit.positionOf(victim, includeDead = true) ?: run.lastPos[victim], now)
    }

    private fun dropPile(run: Run, match: Match, victim: PlayerId, at: Vec3?, now: Long) {
        val gems = run.carried.remove(victim)?.takeIf { it.isNotEmpty() } ?: return
        run.lastPos.remove(victim)
        kit.uncarry(victim)
        val landing = at?.takeIf { run.bases.values.minOf { r -> r.min.y } - VOID_MARGIN < it.y }
        if (landing == null) {
            returnToPedestals(run, match, gems)
            return
        }
        val pile = Pile(run.nextPileId++, landing, gems.toMutableList(), now)
        run.piles += pile
        kit.marker(match.id, "$PILE_KEY${pile.id}", run.stage.world, landing, LOOK.copy(loose = true))
        tellMatch(match, KEY_DROPPED, mapOf("team" to (teamOf(match, victim)?.value ?: "")))
        kit.broadcast(match.teams.flatMap { it.members }, PresentationMoments.OBJECTIVE_DROP, at = landing, world = run.stage.world)
    }

    private fun expirePile(run: Run, match: Match, pile: Pile) {
        run.piles.remove(pile)
        kit.unmarker(match.id, "$PILE_KEY${pile.id}")
        returnToPedestals(run, match, pile.owners)
    }

    private fun returnToPedestals(run: Run, match: Match, gems: List<TeamId>) {
        gems.forEach { owner -> run.onPedestal[owner] = (run.onPedestal[owner] ?: 0) + 1 }
        gems.distinct().forEach { showPedestal(run, match, it) }
        tellMatch(match, KEY_RETURNED)
        val home = gems.firstOrNull()?.let { run.pedestalRegions[it] }?.let { ObjectiveKit.centreOf(it, 1.0) }
        kit.broadcast(match.teams.flatMap { it.members }, PresentationMoments.OBJECTIVE_RETURN, at = home, world = run.stage.world)
    }

    private fun showPedestal(run: Run, match: Match, owner: TeamId) {
        val region = run.pedestalRegions[owner] ?: return
        val key = "$PEDESTAL_KEY${owner.value}"
        val left = run.onPedestal[owner] ?: 0
        if (left > 0) {
            val tag = ObjectiveKit.tagOf(ObjectiveKit.colorOf(owner))
            val look = ObjectiveLook(PEDESTAL_BLOCK, ObjectiveKit.colorOf(owner), 0.6, spin = true, label = "<$tag><bold>x$left</bold></$tag>")
            kit.marker(match.id, key, run.stage.world, ObjectiveKit.centreOf(region, 1.0), look)
        } else {
            kit.unmarker(match.id, key)
        }
    }

    // ---- slow loop --------------------------------------------------------------

    override fun tickRun(run: Run, match: Match, tick: Long) {
        val now = env?.let { it.now() } ?: return
        val world = run.stage.world
        for (pile in run.piles) kit.beam(world, pile.position, null)
        run.pedestalRegions.keys.forEach { showPedestal(run, match, it) }
        for ((owner, region) in run.pedestalRegions) {
            if ((run.onPedestal[owner] ?: 0) > 0) kit.beam(world, ObjectiveKit.centreOf(region), ObjectiveKit.colorOf(owner))
        }
        for (team in match.teams) {
            for (member in team.members) {
                statusBar(run, member, team.id)
                compass(run, member, team.id)
            }
        }
        warnOwners(run, match, now)
    }

    private fun statusBar(run: Run, viewer: PlayerId, team: TeamId) {
        val left = countOf(run, team)
        kit.statusBar(
            viewer, BAR,
            mapOf("count" to run.carried[viewer].orEmpty().size.toString(), "left" to (run.onPedestal[team] ?: 0).toString(), "remaining" to left.toString()),
            1.0 - left.toDouble() / countStart(), ObjectiveKit.barColorOf(ObjectiveKit.colorOf(team)),
        )
    }

    private fun compass(run: Run, viewer: PlayerId, team: TeamId) {
        val from = kit.positionOf(viewer) ?: return
        if (run.carried[viewer].orEmpty().isNotEmpty()) {
            val home = run.bases[team] ?: return
            kit.compass(viewer, ObjectiveKit.centreOf(home), HINT_BANK)
            return
        }
        val loot = run.pedestalRegions.filter { (owner, _) -> owner != team && (run.onPedestal[owner] ?: 0) > 0 }.values.map { ObjectiveKit.centreOf(it) } + run.piles.map { it.position }
        val nearest = loot.minByOrNull { ObjectiveKit.flatDistance(from, it) } ?: return
        kit.compass(viewer, nearest, HINT_STEAL)
    }

    /** An enemy near a pedestal that still holds jewels: its owners are warned every two seconds. */
    private fun warnOwners(run: Run, match: Match, now: Long) {
        for ((owner, region) in run.pedestalRegions) {
            if ((run.onPedestal[owner] ?: 0) <= 0 || !run.due("warn-${owner.value}", now, WARN_INTERVAL_TICKS)) continue
            val centre = ObjectiveKit.centreOf(region)
            val intruder = match.teams.filter { it.id != owner }.flatMap { it.members }
                .firstNotNullOfOrNull { p -> kit.positionOf(p)?.takeIf { ObjectiveKit.flatDistance(it, centre) <= kit.warnDistance } }
                ?: continue
            kit.broadcast(match.teams.firstOrNull { it.id == owner }?.members.orEmpty(), PresentationMoments.OBJECTIVE_WARN, at = intruder, world = run.stage.world)
        }
    }

    override fun holdsObjective(run: Run, match: Match, team: TeamId): Boolean =
        run.carried.any { (carrier, gems) -> gems.isNotEmpty() && teamOf(match, carrier) == team }

    override fun hudLines(run: Run, match: Match): List<HudLine> = emptyList()

    override fun snapshotOf(run: Run, match: Match): ObjectiveSnapshot = ObjectiveSnapshot.Jewels(
        onPedestal = run.onPedestal.toMap(),
        pedestals = run.pedestalRegions.mapValues { ObjectiveKit.centreOf(it.value, 1.0) },
        carried = run.carried.mapValues { it.value.size },
    )

    override fun finish(run: Run) {
        run.pedestals.values.forEach { setBlock(run, it, floorBlock()) }
    }

    public companion object {
        public val ID: GameModeId = GameModeId("steal-jewelry")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.steal-jewelry.title")
        private val KEY_STOLEN: MessageKey = MessageKey("astera.gamemode.steal-jewelry.stolen")
        private val KEY_BANKED: MessageKey = MessageKey("astera.gamemode.steal-jewelry.banked")
        private val KEY_RECOVERED: MessageKey = MessageKey("astera.gamemode.steal-jewelry.recovered")
        private val KEY_DROPPED: MessageKey = MessageKey("astera.gamemode.steal-jewelry.dropped")
        private val KEY_RETURNED: MessageKey = MessageKey("astera.gamemode.steal-jewelry.returned")
        private val LABEL_STEAL: MessageKey = MessageKey("astera.objective.channel.steal-jewelry.steal")
        private val LABEL_BANK: MessageKey = MessageKey("astera.objective.channel.steal-jewelry.bank")
        private val BAR: MessageKey = MessageKey("astera.objective.bar.steal-jewelry")
        private val HINT_BANK: MessageKey = MessageKey("astera.objective.hint.steal-jewelry.bank")
        private val HINT_STEAL: MessageKey = MessageKey("astera.objective.hint.steal-jewelry.steal")
        private val LOOK: ObjectiveLook = ObjectiveLook("minecraft:diamond_block", "aqua", 0.5)
        private const val PEDESTAL_PREFIX: String = "jewelry-"
        private const val BASE_PREFIX: String = "base-"
        private const val PEDESTAL_BLOCK: String = "minecraft:diamond_block"
        private const val BANK: String = "bank"
        private const val STEAL: String = "steal:"
        private const val PILE: String = "pile:"
        private const val PILE_KEY: String = "pile-"
        private const val PEDESTAL_KEY: String = "pedestal-"
        private const val DEFAULT_GEMS: Int = 6
        private const val EVENT_GEM: String = "gem"
        private const val DEFAULT_STEAL_TICKS: Int = 80
        private const val FAST_TICKS: Long = 5L
        private const val TRAIL_TICKS: Long = 5L
        private const val WARN_INTERVAL_TICKS: Long = 40L
        private const val REACH_HEIGHT: Double = 3.0
        private const val VOID_MARGIN: Double = 8.0
    }
}
