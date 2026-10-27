package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.status.StatusEffectId
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
import com.astarworks.astera.match.application.gamemode.siege.TntCarrier
import com.astarworks.astera.match.application.port.outbound.ObjectiveBarColor
import com.astarworks.astera.match.application.port.outbound.ObjectiveLook
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.mode.GameModeId
import kotlin.math.abs

/**
 * Carry TNT (Rainmaker / ガチホコ): one TNT sits in the `tnt-spawn` zone behind a shield of `shield_hp`; players
 * standing there (and damage dealt within `shield_damage_radius`) wear it down, and once it is broken, standing in the
 * zone for `channel_ticks` picks the TNT up. The carrier is slow, glowing, cannot cast skills (silence) and wears the TNT.
 *
 * The carrier's team count is `count_start` minus the furthest share of the route (spawn, `checkpoint-<enemy>-<n>`,
 * `deliver-<enemy>`, measured along the polyline) any of its carriers covered; the next unheld checkpoint caps the
 * progress until the carrier held it for `checkpoint_hold_ticks` (pause + `checkpoint_shield_seconds` of immunity).
 * Holding the enemy `deliver-<team>` pedestal for `deliver_channel_ticks` is a knockout win.
 *
 * A carrier who falls drops the TNT; after `drop_return_seconds` it returns to the centre (new shield), checkpoints
 * already held stay held. Overtime runs while the trailing team carries the TNT.
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
        /** Delivery pedestal per base owner: the carrier of another team scores by holding it. */
        public val delivers: Map<TeamId, Region>,
        /** Checkpoints of the route toward each base owner, in name order. */
        public val checkpoints: Map<TeamId, List<Region>>,
        public var shield: Double,
        /** Route polyline toward each base owner: spawn centre, its checkpoints in order, its deliver pedestal. */
        public val routes: Map<TeamId, List<Vec3>>,
    ) : SiegeRun(matchId, stage) {
        public val tnt: TntCarrier = TntCarrier()

        /** Furthest share (0..1) of the route toward the enemy any carrier of the team covered. */
        public val progress: MutableMap<TeamId, Double> = mutableMapOf()

        /** Checkpoint names already held, per base owner the route leads to. */
        public val passed: MutableMap<TeamId, MutableSet<String>> = mutableMapOf()

        /** Last place the carrier was seen alive (where the TNT lands if the carrier vanishes). */
        public var lastCarrierPos: Vec3? = null

        /** Whether the TNT block still stands on the spawn pedestal. */
        public var pedestalLit: Boolean = true
    }

    private fun shieldMax(): Double = (env?.int("shield_hp", 200) ?: 200).toDouble()

    private fun shieldDrainPerSecond(): Double = (env?.int("shield_drain_per_second", 10) ?: 10).toDouble()

    private fun shieldDamageRadius(): Double = (env?.int("shield_damage_radius", 6) ?: 6).toDouble()

    private fun shieldDamagePct(): Double = (env?.int("shield_damage_pct", 100) ?: 100) / 100.0

    private fun checkpointHoldTicks(): Long = (env?.int("checkpoint_hold_ticks", 60) ?: 60).toLong()

    private fun deliverChannelTicks(): Long = (env?.int("deliver_channel_ticks", 40) ?: 40).toLong()

    private fun checkpointShieldTicks(): Int = (env?.int("checkpoint_shield_seconds", 4) ?: 4) * 20

    override fun createRun(match: Match, stage: Stage): Run? {
        val needed = listOf(SPAWN_REGION) + match.teams.flatMap { listOf("$BASE_PREFIX${it.id.value}", "$DELIVER_REGION_PREFIX${it.id.value}") }
        val missing = needed.filter { stage.region(it) == null }
        if (missing.isNotEmpty()) return missingRegions(stage, missing)
        val spawn = stage.region(SPAWN_REGION)!!
        val bases = match.teams.associate { it.id to stage.region("$BASE_PREFIX${it.id.value}")!! }
        val delivers = match.teams.associate { it.id to stage.region("$DELIVER_REGION_PREFIX${it.id.value}")!! }
        val checkpoints = match.teams.associate { team ->
            team.id to stage.regionsWithPrefix("$CHECKPOINT_PREFIX${team.id.value}-").sortedBy { it.name }
        }
        val routes = match.teams.associate { team ->
            team.id to (listOf(ObjectiveKit.centreOf(spawn)) + checkpoints.getValue(team.id).map { ObjectiveKit.centreOf(it) } + ObjectiveKit.centreOf(delivers.getValue(team.id)))
        }
        return Run(match.id, stage, cellOf(spawn), spawn, bases, delivers, checkpoints, shieldMax(), routes)
    }

    override fun start(run: Run, match: Match) {
        setBlock(run, run.spawnCell, MARKER_BLOCK)
        for ((owner, list) in run.checkpoints) for ((index, cp) in list.withIndex()) {
            kit.marker(match.id, checkpointKey(cp), run.stage.world, ObjectiveKit.centreOf(cp, 1.4), ObjectiveLook("minecraft:yellow_concrete", "yellow", 0.5, spin = true, label = "<yellow><bold>#${index + 1}</bold></yellow>"))
        }
        refreshMarker(run, match)
    }

    private fun checkpointKey(cp: Region): String = "checkpoint:${cp.name}"

    // ---- fast loop: shield, respawn, carrier upkeep, drop timer, channels --------

    override fun fastTick(run: Run, match: Match) {
        if (run.ended) return
        val e = env ?: return
        val now = e.now()
        drainShield(run, match)
        val carrier = run.tnt.carrier
        if (carrier != null) {
            val pos = kit.positionOf(carrier)
            val team = teamOf(match, carrier)
            if (pos == null) {
                dropCarrier(run, match, carrier, run.lastCarrierPos)
            } else {
                run.lastCarrierPos = pos
                if (team != null) {
                    if (run.due("trail", now, TRAIL_TICKS)) kit.trail(run.stage.world, pos, ObjectiveKit.colorOf(team))
                    advanceProgress(run, match, team, pos)
                    if (run.ended) return
                }
                if (run.due("silence", now, SILENCE_REFRESH_TICKS)) silence(carrier)
            }
        } else if (run.tnt.droppedAt != null && now - run.tnt.droppedTick >= kit.dropReturnTicks) {
            returnHome(run, match)
        }
        for (team in match.teams) {
            for (member in team.members) {
                if (run.ended) return
                val target = kit.positionOf(member)?.let { targetOf(run, match, member, team.id, it) }
                if (kit.channel(member, target, labelOf(target), colorOf(target), needOf(target))) complete(run, match, member, team.id, target ?: continue)
            }
        }
    }

    /** Whether the TNT lies on its home pedestal (not carried or dropped). */
    private fun atHome(run: Run): Boolean = run.tnt.carrier == null && run.tnt.droppedAt == null

    private fun drainShield(run: Run, match: Match) {
        if (run.shield <= 0.0 || !atHome(run)) return
        var present = 0
        for (team in match.teams) for (member in team.members) {
            val pos = kit.positionOf(member) ?: continue
            if (run.spawn.contains(pos)) present++
        }
        if (present == 0) return
        hitShield(run, match, present * shieldDrainPerSecond() * FAST_TICKS / 20.0)
    }

    private fun hitShield(run: Run, match: Match, amount: Double) {
        if (run.shield <= 0.0 || !atHome(run)) return
        run.shield = (run.shield - amount).coerceAtLeast(0.0)
        if (run.shield > 0.0) return
        tellMatch(match, KEY_SHIELD_BROKEN)
        kit.burst(run.stage.world, ObjectiveKit.centreOf(run.spawn, 1.0), "aqua")
        refreshMarker(run, match)
    }

    override fun damaged(run: Run, match: Match, victim: PlayerId, attacker: PlayerId?, amount: Double) {
        if (attacker == null || run.shield <= 0.0 || !atHome(run)) return
        val pos = kit.positionOf(attacker) ?: return
        if (ObjectiveKit.flatDistance(pos, ObjectiveKit.centreOf(run.spawn)) > shieldDamageRadius()) return
        hitShield(run, match, amount * shieldDamagePct())
    }

    /** The next checkpoint of the route toward [owner] that is not yet held. */
    private fun nextCheckpoint(run: Run, owner: TeamId): Region? =
        run.checkpoints[owner].orEmpty().firstOrNull { it.name !in run.passed[owner].orEmpty() }

    /** The enemy base owner [team] carries toward: the nearest deliver pedestal of another team to [from]. */
    private fun goalOwner(run: Run, team: TeamId, from: Vec3): TeamId? =
        run.delivers.entries.filter { it.key != team }.minByOrNull { ObjectiveKit.flatDistance(from, ObjectiveKit.centreOf(it.value)) }?.key

    /** The checkpoint of the route [carrierTeam] walks that [pos] stands in and that is next to be held, as `cp:<owner>:<name>`. */
    private fun pendingCheckpointAt(run: Run, carrierTeam: TeamId, pos: Vec3): String? {
        val owner = goalOwner(run, carrierTeam, pos) ?: return null
        val cp = nextCheckpoint(run, owner)?.takeIf { it.contains(pos) } ?: return null
        return "$CHECKPOINT_TARGET${owner.value}:${cp.name}"
    }

    private fun routeDone(run: Run, owner: TeamId): Boolean =
        run.checkpoints[owner].orEmpty().all { it.name in run.passed[owner].orEmpty() }

    private fun targetOf(run: Run, match: Match, player: PlayerId, team: TeamId, pos: Vec3): String? {
        val tnt = run.tnt
        if (tnt.carrier == player) {
            pendingCheckpointAt(run, team, pos)?.let { return it }
            val hit = run.delivers.entries.firstOrNull { (owner, region) -> owner != team && region.contains(pos) } ?: return null
            return if (routeDone(run, hit.key)) "$DELIVER_PREFIX${hit.key.value}" else null
        }
        if (tnt.carrier != null) return null
        val dropped = tnt.droppedAt
        if (dropped == null && run.shield > 0.0) return null
        val inReach = if (dropped != null) {
            ObjectiveKit.flatDistance(pos, dropped) <= kit.pickupRadius && abs(pos.y - dropped.y) <= REACH_HEIGHT
        } else {
            run.spawn.contains(pos)
        }
        return if (inReach) PICKUP else null
    }

    private fun labelOf(target: String?): MessageKey = when {
        target == PICKUP -> LABEL_PICKUP
        target?.startsWith(CHECKPOINT_TARGET) == true -> LABEL_CHECKPOINT
        else -> LABEL_DELIVER
    }

    private fun colorOf(target: String?): ObjectiveBarColor =
        if (target?.startsWith(CHECKPOINT_TARGET) == true) ObjectiveBarColor.YELLOW else ObjectiveBarColor.GREEN

    private fun needOf(target: String?): Long = when {
        target == PICKUP -> kit.channelTicks
        target?.startsWith(CHECKPOINT_TARGET) == true -> checkpointHoldTicks()
        else -> deliverChannelTicks()
    }

    private fun complete(run: Run, match: Match, player: PlayerId, team: TeamId, target: String) {
        when {
            target == PICKUP -> pickedUp(run, match, player, team)
            target.startsWith(CHECKPOINT_TARGET) -> {
                val (owner, name) = target.removePrefix(CHECKPOINT_TARGET).split(':', limit = 2)
                reachedCheckpoint(run, match, player, owner, name)
            }
            else -> delivered(run, match, player, team, target.removePrefix(DELIVER_PREFIX))
        }
    }

    private fun reachedCheckpoint(run: Run, match: Match, player: PlayerId, owner: String, name: String) {
        if (!run.tnt.isCarrying(player)) return
        val ownerId = run.checkpoints.keys.firstOrNull { it.value == owner } ?: return
        val list = run.checkpoints.getValue(ownerId)
        val region = list.firstOrNull { it.name == name } ?: return
        if (nextCheckpoint(run, ownerId) != region) return
        run.passed.getOrPut(ownerId) { mutableSetOf() } += name
        val left = list.count { it.name !in run.passed.getValue(ownerId) }
        kit.burst(run.stage.world, ObjectiveKit.centreOf(region, 1.0), "yellow")
        kit.toast(player, TOAST_CHECKPOINT, mapOf("n" to (list.indexOf(region) + 1).toString(), "left" to left.toString()), ttlTicks = 60)
        shieldCarrier(player)
    }

    /** The checkpoint re-shield: the carrier is immune to damage for `checkpoint_shield_seconds`. */
    private fun shieldCarrier(player: PlayerId) {
        val container = env?.statuses?.invoke() ?: return
        container.apply(StatusEffectInstance(IMMUNITY_SPEC, player, checkpointShieldTicks(), 1, env?.now() ?: 0L))
    }

    /** The carrier cannot use weapons: a long silence, refreshed while they carry. */
    private fun silence(player: PlayerId) {
        val container = env?.statuses?.invoke() ?: return
        container.apply(StatusEffectInstance(SILENCE_SPEC, player, SILENCE_TICKS, 1, env?.now() ?: 0L))
    }

    private fun unsilence(player: PlayerId) {
        env?.statuses?.invoke()?.remove(player, SILENCE_SPEC.id)
    }

    private fun pickedUp(run: Run, match: Match, player: PlayerId, team: TeamId) {
        if (!run.tnt.pickUp(player)) return
        kit.carry(player, 1, LOOK)
        silence(player)
        run.lastCarrierPos = kit.positionOf(player)
        refreshMarker(run, match)
        tellMatch(match, KEY_PICKED_UP, mapOf("team" to team.value))
        val at = run.lastCarrierPos
        kit.broadcast(listOf(player), PresentationMoments.OBJECTIVE_PICKUP, at = at, world = run.stage.world)
        announceObjectiveShift(run, match, team, at)
    }

    // ---- route progress -> count ------------------------------------------------

    /** Furthest progress of [pos] along the polyline [route] (0..1) given the checkpoints [held]; capped at the next unheld checkpoint. */
    private fun routeProgress(route: List<Vec3>, held: Int, pos: Vec3): Double {
        val lengths = route.zipWithNext { a, b -> ObjectiveKit.flatDistance(a, b) }
        val total = lengths.sum().takeIf { it > 0.0 } ?: return 0.0
        var best = 0.0
        var acc = 0.0
        for ((i, len) in lengths.withIndex()) {
            val a = route[i]
            val b = route[i + 1]
            val dx = b.x - a.x
            val dz = b.z - a.z
            val t = if (len <= 0.0) 0.0 else (((pos.x - a.x) * dx + (pos.z - a.z) * dz) / (len * len)).coerceIn(0.0, 1.0)
            val px = a.x + t * dx
            val pz = a.z + t * dz
            val off = kotlin.math.hypot(pos.x - px, pos.z - pz)
            if (off <= ROUTE_CORRIDOR) best = maxOf(best, (acc + t * len) / total)
            acc += len
        }
        val cap = if (held < route.size - 2) lengths.take(held + 1).sum() / total else GOAL_CAP
        return minOf(best, cap)
    }

    private fun advanceProgress(run: Run, match: Match, team: TeamId, pos: Vec3) {
        val owner = goalOwner(run, team, pos) ?: return
        val route = run.routes[owner] ?: return
        val held = run.checkpoints[owner].orEmpty().count { it.name in run.passed[owner].orEmpty() }
        val p = routeProgress(route, held, pos)
        val furthest = maxOf(run.progress[team] ?: 0.0, p)
        run.progress[team] = furthest
        val target = countStart() - Math.round(furthest * countStart()).toInt()
        val owed = countOf(run, team) - target
        if (owed > 0) countDown(run, match, team, owed, at = pos)
    }

    private fun delivered(run: Run, match: Match, player: PlayerId, team: TeamId, baseTeam: String) {
        if (!run.tnt.isCarrying(player)) return
        val base = run.delivers.entries.firstOrNull { it.key.value == baseTeam }?.takeIf { it.key != team } ?: return
        if (!routeDone(run, base.key)) return
        kit.uncarry(player)
        unsilence(player)
        run.tnt.reset()
        refreshMarker(run, match)
        val args = mapOf("team" to team.value)
        tellMatch(match, KEY_DELIVERED, args)
        val burst = ObjectiveKit.centreOf(base.value, 1.0)
        val ours = match.teams.firstOrNull { it.id == team }?.members.orEmpty()
        val theirs = match.teams.filter { it.id != team }.flatMap { it.members }
        kit.broadcast(ours, PresentationMoments.OBJECTIVE_DELIVER, args, burst, run.stage.world)
        kit.broadcast(theirs, PresentationMoments.OBJECTIVE_DELIVER_AGAINST, args, burst, run.stage.world)
        run.progress[team] = 1.0
        countDown(run, match, team, countOf(run, team), at = burst)
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
        unsilence(victim)
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

    /** A dropped TNT timed out: it returns to the centre behind a new shield; checkpoints already held stay held. */
    private fun returnHome(run: Run, match: Match) {
        run.tnt.reset()
        run.shield = shieldMax()
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
        when {
            pos == null -> kit.unmarker(match.id, MARKER_KEY)
            run.shield > 0.0 && run.tnt.droppedAt == null ->
                kit.marker(match.id, MARKER_KEY, run.stage.world, pos, LOOK.copy(glow = "aqua", loose = true, label = "<aqua><bold>${run.shield.toInt()}</bold></aqua>"))
            else -> kit.marker(match.id, MARKER_KEY, run.stage.world, pos, LOOK.copy(loose = true))
        }
        val lit = atHome(run)
        if (lit != run.pedestalLit) {
            run.pedestalLit = lit
            setBlock(run, run.spawnCell, if (lit) MARKER_BLOCK else floorBlock())
        }
    }

    // ---- slow loop: beams, HUD bars, compass, warnings --------------------------

    override fun tickRun(run: Run, match: Match, tick: Long) {
        val now = env?.let { it.now() } ?: return
        val world = run.stage.world
        val loose = loosePos(run)
        if (loose != null) kit.beam(world, loose, null)
        for ((owner, region) in run.delivers) kit.beam(world, ObjectiveKit.centreOf(region), ObjectiveKit.colorOf(owner))
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
        if (run.shield > 0.0 && atHome(run)) {
            kit.bar(viewer, SHIELD_BAR, BAR_SHIELD, mapOf("hp" to run.shield.toInt().toString()), run.shield / shieldMax(), ObjectiveBarColor.BLUE)
        } else {
            kit.hideBar(viewer, SHIELD_BAR)
        }
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
                val goal = run.delivers.entries.filter { it.key != team }.minByOrNull { ObjectiveKit.flatDistance(from, ObjectiveKit.centreOf(it.value)) } ?: return
                val held = run.passed[goal.key].orEmpty()
                val next = run.checkpoints[goal.key].orEmpty().firstOrNull { it.name !in held }
                if (next != null) {
                    kit.compass(viewer, ObjectiveKit.centreOf(next), HINT_CHECKPOINT)
                } else {
                    kit.compass(viewer, ObjectiveKit.centreOf(goal.value), HINT_DELIVER, mapOf("team" to goal.key.value))
                }
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
        for ((owner, region) in run.delivers) {
            if (owner == carrierTeam || ObjectiveKit.flatDistance(pos, ObjectiveKit.centreOf(region)) > kit.warnDistance) continue
            val defenders = match.teams.firstOrNull { it.id == owner }?.members.orEmpty()
            kit.broadcast(defenders, PresentationMoments.OBJECTIVE_WARN, at = pos, world = run.stage.world)
        }
    }

    override fun holdsObjective(run: Run, match: Match, team: TeamId): Boolean =
        run.tnt.carrier?.let { teamOf(match, it) } == team

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val carrierTeam = run.tnt.carrier?.let { c -> match.teams.firstOrNull { c in it.members }?.id }
        val carrier = if (carrierTeam != null) {
            HudLine(MessageKey("astera.hud.sidebar.carry-tnt.carried"), team = carrierTeam)
        } else {
            HudLine(MessageKey("astera.hud.sidebar.carry-tnt.loose"))
        }
        return listOf(carrier)
    }

    override fun snapshotOf(run: Run, match: Match): ObjectiveSnapshot =
        ObjectiveSnapshot.Rainmaker(
            position = run.tnt.carrier?.let { kit.positionOf(it) } ?: loosePos(run),
            carrier = run.tnt.carrier,
            shieldHp = run.shield,
            shieldMax = shieldMax(),
            progress = match.teams.associate { it.id to (run.progress[it.id] ?: 0.0) },
            nextWaypoint = match.teams.mapNotNull { t ->
                val owner = match.teams.firstOrNull { it.id != t.id }?.id ?: return@mapNotNull null
                val next = nextCheckpoint(run, owner)?.let { ObjectiveKit.centreOf(it) } ?: ObjectiveKit.centreOf(run.delivers.getValue(owner))
                t.id to next
            }.toMap(),
            goal = match.teams.mapNotNull { t ->
                val owner = match.teams.firstOrNull { it.id != t.id }?.id ?: return@mapNotNull null
                t.id to ObjectiveKit.centreOf(run.delivers.getValue(owner))
            }.toMap(),
        )

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
        private val KEY_SHIELD_BROKEN: MessageKey = MessageKey("astera.gamemode.carry-tnt.shield-broken")
        private val TOAST_CHECKPOINT: MessageKey = MessageKey("astera.objective.toast.carry-tnt.checkpoint")
        private val LABEL_CHECKPOINT: MessageKey = MessageKey("astera.objective.channel.carry-tnt.checkpoint")
        private val BAR_SHIELD: MessageKey = MessageKey("astera.objective.bar.carry-tnt.shield")
        private val LABEL_PICKUP: MessageKey = MessageKey("astera.objective.channel.carry-tnt.pickup")
        private val LABEL_DELIVER: MessageKey = MessageKey("astera.objective.channel.carry-tnt.deliver")
        private val BAR_LOOSE: MessageKey = MessageKey("astera.objective.bar.carry-tnt.loose")
        private val BAR_CARRIED: MessageKey = MessageKey("astera.objective.bar.carry-tnt.carried")
        private val BAR_DROPPED: MessageKey = MessageKey("astera.objective.bar.carry-tnt.dropped")
        private val HINT_CHECKPOINT: MessageKey = MessageKey("astera.objective.hint.carry-tnt.checkpoint")
        private val HINT_DELIVER: MessageKey = MessageKey("astera.objective.hint.carry-tnt.deliver")
        private val HINT_ESCORT: MessageKey = MessageKey("astera.objective.hint.carry-tnt.escort")
        private val HINT_STOP: MessageKey = MessageKey("astera.objective.hint.carry-tnt.stop")
        private val HINT_GRAB: MessageKey = MessageKey("astera.objective.hint.carry-tnt.grab")
        private val LOOK: ObjectiveLook = ObjectiveLook("minecraft:tnt", "white", 0.8)
        private const val SPAWN_REGION: String = "tnt-spawn"
        private const val BASE_PREFIX: String = "base-"
        private const val DELIVER_REGION_PREFIX: String = "deliver-"
        private const val CHECKPOINT_PREFIX: String = "checkpoint-"
        private const val CHECKPOINT_TARGET: String = "cp:"
        private const val SHIELD_BAR: String = "shield"
        private const val MARKER_BLOCK: String = "minecraft:tnt"
        private const val MARKER_KEY: String = "tnt"
        private const val PICKUP: String = "pickup"
        private const val DELIVER_PREFIX: String = "deliver:"
        private const val ROUTE_CORRIDOR: Double = 14.0
        private const val GOAL_CAP: Double = 0.99
        private const val SILENCE_TICKS: Int = 20 * 60
        private const val SILENCE_REFRESH_TICKS: Long = 200L
        private val IMMUNITY_SPEC: StatusEffectSpec = StatusEffectSpec(StatusEffectId("immunity"), MessageKey("astera.status.immunity.name"), StatusKind.STACK_BUFF, 0.0, false, 1)
        private val SILENCE_SPEC: StatusEffectSpec = StatusEffectSpec(StatusEffectId("silence"), MessageKey("astera.status.silence.name"), StatusKind.SILENCE, 0.0, false, 1)
        private const val FAST_TICKS: Long = 5L
        private const val TRAIL_TICKS: Long = 5L
        private const val WARN_INTERVAL_TICKS: Long = 40L
        private const val REACH_HEIGHT: Double = 3.0
        private const val VOID_MARGIN: Double = 8.0
    }
}
