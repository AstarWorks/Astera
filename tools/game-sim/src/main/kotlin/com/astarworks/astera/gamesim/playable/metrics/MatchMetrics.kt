package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.NexusDamaged
import com.astarworks.astera.core.domain.event.NexusDestroyed
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.SimEntityKind
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.event.StarsCollected
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.floor
import kotlin.math.sqrt

/** Result of one match: [json] is the metrics.json document, [scalars] the numbers [MassPlay] aggregates (null = n/a, see [notAvailable]). */
class MatchMetricsResult(
    val json: JsonObject,
    val scalars: Map<String, Double?>,
    val notAvailable: Map<String, String>,
)

/**
 * Fun / balance metrics of one headless match (design fable-playable-sim section 4.1). Derived only from
 * [GameSim.events], entity positions and the pillar HP in `IStructureTargets`. Whoever drives the match calls [onTick]
 * once per sim tick; [finish] returns the result. A column GameSim cannot expose is emitted as n/a with a reason.
 *
 * Optional seats: [roleOf] (bot RoleAssigner role) and [decisionKindOf] (bot `Dest.kind` / Claude act kind) come from the
 * bot seats; without them role columns are n/a and decision density falls back to activity-class transitions.
 */
class MatchMetrics(
    private val sim: GameSim,
    private val roleOf: ((PlayerId) -> String?)? = null,
    private val decisionKindOf: ((PlayerId) -> String?)? = null,
    private val leadSampleTicks: Int = 600,
    private val gridSize: Int = 4,
) {
    private enum class Activity { FIGHT, MINE, MOVE, IDLE }

    private class PlayerState(val id: PlayerId, val name: String) {
        var lastPos: Vec3? = null
        var lastActivity: Activity? = null
        var lastDecision: String? = null
        var activeTicks = 0L
        val ticksByActivity = IntArray(Activity.entries.size)
        var transitions = 0
    }

    private var cursor = 0
    private var matchId: MatchId? = null
    private var stageId: StageId? = null
    private var startTick: Long? = null
    private var endTick: Long? = null
    private var ended: MatchEnded? = null
    private var lastTick = 0L

    private var stage: Stage? = null
    private var pillarIds: Map<PlayerId, TeamId> = emptyMap()
    private val pillarHp = HashMap<PlayerId, Double>()
    private var bounds: DoubleArray? = null // minX, minZ, maxX, maxZ

    private val players = LinkedHashMap<PlayerId, PlayerState>()
    private val teamOf = HashMap<PlayerId, TeamId>()
    private var firstFightTick: Long? = null
    private val normaLevel = HashMap<PlayerId, Int>()

    private val kills = HashMap<String, Int>()
    private val killsByItem = HashMap<String, Int>()
    private var killTotal = 0
    private val killsByTeam = HashMap<String, Int>()
    private val killsByPlayer = HashMap<String, Int>()
    private val pillarDamageByKey = HashMap<String, Double>()
    private val pillarDamageByItem = HashMap<String, Double>()
    private var pillarDamageTotal = 0.0
    private var starsSupplied = 0
    private var starsCollected = 0

    private var oreByGroup: Map<String, Double> = emptyMap()
    private val contentionWindowTicks = 2_400L

    private val heat = Array(gridSize) { IntArray(gridSize) }
    private val leadSamples = mutableListOf<Triple<Long, TeamId?, Map<String, Double>>>()
    private var leader: TeamId? = null
    private var leadChanges = 0

    private var starPositions = HashMap<String, Vec3>()
    private val pickupPositions = mutableListOf<Vec3>()

    /** The match this collector found (null until MatchStarted was seen); [FrameRenderer] needs both. */
    val currentMatchId: MatchId? get() = matchId
    val currentStageId: StageId? get() = stageId

    fun onTick(tick: Long) {
        lastTick = tick
        val n = sim.events.size
        val fresh = sim.events.subList(cursor, n).toList()
        cursor = n
        val fighters = HashSet<PlayerId>()
        val miners = HashSet<PlayerId>()
        var collected = 0
        val pillarAttackers = HashSet<PlayerId>()
        val pillarDirect = HashMap<PlayerId, Double>()
        for (e in fresh) when (e) {
            is MatchStarted -> if (matchId == null) onMatchStarted(e)
            is MatchEnded -> if (e.matchId == matchId && ended == null) { ended = e; endTick = e.atTick }
            is DamageDealt -> {
                val attacker = e.attacker
                // Pillar damage of every kind (weapon, tool skill, hand-break) is attributed from NexusDamaged / NexusDestroyed below.
                if (e.target !in pillarIds.keys && attacker != null && isPlayer(attacker) && isPlayer(e.target)) {
                    fighters += attacker; fighters += e.target
                    if (firstFightTick == null) firstFightTick = e.atTick
                }
            }
            is BlockBroken -> miners += e.playerId
            is NexusDamaged -> if (e.matchId == matchId) e.attackerId?.let { pillarAttackers += it; pillarDirect.merge(it, e.damageAmount.toDouble(), Double::plus) }
            is PlayerKilled -> onKill(e)
            is NexusDestroyed -> if (e.matchId == matchId) e.attackerId?.let { pillarAttackers += it; pillarDirect.merge(it, e.previousHp.toDouble(), Double::plus) }
            is StarSupplied -> if (e.matchId == matchId) starsSupplied += e.amount
            is StarsCollected -> if (e.matchId == matchId) { starsCollected += e.amount; collected++ }
            is NormaLevelUp -> if (e.matchId == matchId) normaLevel[e.playerId] = maxOf(normaLevel[e.playerId] ?: 0, e.level)
            else -> {}
        }
        val started = startTick ?: return
        if (endTick != null && tick > endTick!!) return
        trackPillars(pillarAttackers, pillarDirect)
        trackStars(collected)
        trackPlayers(fighters, miners)
        if (tick > started && (tick - started) % leadSampleTicks == 0L) sampleLead(tick)
    }

    private fun isPlayer(id: PlayerId): Boolean = sim.world.find(id)?.kind == SimEntityKind.PLAYER

    private fun onMatchStarted(e: MatchStarted) {
        matchId = e.matchId; stageId = e.stageId; startTick = e.atTick
        val st = sim.services.get<IStageRegistry>()?.find(e.stageId)
        stage = st
        if (st != null) {
            pillarIds = st.pillars.associate {
                AnnihilationPillarTargets.idOf("${e.matchId.value}-${it.team.value}-${it.tier.ordinalValue}") to it.team
            }
            val xs = st.pillars.map { it.position.x } + (st.layout?.islands?.map { it.x.toDouble() } ?: emptyList())
            val zs = st.pillars.map { it.position.z } + (st.layout?.islands?.map { it.z.toDouble() } ?: emptyList())
            if (xs.isNotEmpty()) bounds = doubleArrayOf(xs.min() - 24, zs.min() - 24, xs.max() + 24, zs.max() + 24)
        }
    }

    private fun group(id: PlayerId?): String =
        if (id == null) "environment" else roleOf?.invoke(id) ?: "team:${sim.world.find(id)?.team?.value ?: "?"}"

    private fun onKill(e: PlayerKilled) {
        if (!isPlayer(e.victim)) return
        killTotal++
        kills.merge(group(e.killer), 1, Int::plus)
        e.killer?.let { k ->
            val ent = sim.world.find(k)
            killsByTeam.merge(ent?.team?.value ?: "?", 1, Int::plus)
            killsByPlayer.merge(ent?.name ?: "?", 1, Int::plus)
        }
        killsByItem.merge(e.killer?.let { sim.world.find(it)?.heldItem?.value } ?: "none", 1, Int::plus)
    }

    private fun trackPillars(attackers: Set<PlayerId>, direct: Map<PlayerId, Double>) {
        val targets = sim.services.get<IStructureTargets>() ?: return
        var delta = 0.0
        for (id in pillarIds.keys) {
            // null = the match no longer tracks the pillar (ended/cleaned up): not damage, so the remaining HP must not count.
            val hp = targets.hitPoints(id) ?: continue
            val prev = pillarHp[id]
            if (prev != null && hp < prev) delta += prev - hp
            pillarHp[id] = hp
        }
        if (delta <= 0.0) return
        pillarDamageTotal += delta
        val who = attackers.filter { isPlayer(it) }
        val shares: Map<PlayerId?, Double> = when {
            direct.isNotEmpty() -> direct.mapValues { delta * it.value / direct.values.sum() }
            who.isNotEmpty() -> who.associateWith { delta / who.size }
            else -> mapOf(null to delta)
        }
        for ((pid, amount) in shares) {
            pillarDamageByKey.merge(if (pid == null) "unattributed" else group(pid), amount, Double::plus)
            pillarDamageByItem.merge(pid?.let { sim.world.find(it)?.heldItem?.value } ?: "unattributed", amount, Double::plus)
        }
    }

    private fun trackStars(collected: Int) {
        val now = sim.starDrops.lying().associate { it.handle to it.position }
        if (collected > 0) pickupPositions += starPositions.filterKeys { it !in now }.values
        starPositions = HashMap(now)
    }

    private fun trackPlayers(fighters: Set<PlayerId>, miners: Set<PlayerId>) {
        for (entity in sim.world.entities) {
            if (entity.kind != SimEntityKind.PLAYER) continue
            val team = entity.team ?: continue
            teamOf[entity.id] = team
            val st = players.getOrPut(entity.id) { PlayerState(entity.id, entity.name) }
            val prev = st.lastPos
            st.lastPos = entity.position
            if (!sim.isAlive(entity.id)) continue
            val moved = prev != null && prev.distanceSquared(entity.position) > 1e-6
            val act = when {
                entity.id in fighters -> Activity.FIGHT
                entity.id in miners -> Activity.MINE
                moved -> Activity.MOVE
                else -> Activity.IDLE
            }
            st.activeTicks++
            st.ticksByActivity[act.ordinal]++
            if (decisionKindOf != null) {
                val decision = decisionKindOf.invoke(entity.id)
                if (decision != null && decision != st.lastDecision) {
                    if (st.lastDecision != null) st.transitions++
                    st.lastDecision = decision
                }
            } else {
                if (st.lastActivity != null && st.lastActivity != act && act != Activity.IDLE) st.transitions++
                st.lastActivity = act
            }
            bounds?.let { b -> cell(entity, b)?.let { (cx, cz) -> heat[cz][cx]++ } }
        }
    }

    private fun cell(e: SimEntity, b: DoubleArray): Pair<Int, Int>? {
        val fx = (e.position.x - b[0]) / (b[2] - b[0])
        val fz = (e.position.z - b[1]) / (b[3] - b[1])
        if (fx < 0 || fz < 0 || fx >= 1 || fz >= 1) return null
        return floor(fx * gridSize).toInt() to floor(fz * gridSize).toInt()
    }

    /** Team score = standing pillar HP + average Norma level of its players (design 4.1). */
    private fun scores(): Map<String, Double> {
        val targets = sim.services.get<IStructureTargets>()
        val teams = teamOf.values.toSet() + (stage?.pillars?.map { it.team } ?: emptyList())
        return teams.associate { t ->
            val hp = pillarIds.filterValues { it == t }.keys.sumOf { targets?.hitPoints(it) ?: 0.0 }
            val members = teamOf.filterValues { it == t }.keys
            val norma = if (members.isEmpty()) 0.0 else members.map { (normaLevel[it] ?: 0).toDouble() }.average()
            t.value to hp + norma
        }
    }

    private fun sampleLead(tick: Long) {
        val s = scores()
        val top = s.maxByOrNull { it.value }
        val tied = top != null && s.count { it.value == top.value } > 1
        val newLeader = if (top == null || tied) leader else TeamId(top.key)
        if (leader != null && newLeader != null && newLeader != leader) leadChanges++
        leader = newLeader
        leadSamples += Triple(tick, newLeader, s)
    }

    fun finish(): MatchMetricsResult {
        val na = linkedMapOf<String, String>()
        val scalars = linkedMapOf<String, Double?>()
        val start = startTick
        val end = endTick ?: lastTick
        val ticks = if (start == null) 0L else (end - start)
        val seconds = ticks / 20.0
        val minutes = seconds / 60.0
        val playerList = players.values.toList()
        val reason = ended?.reason?.let { it::class.simpleName } ?: "running"
        val winner = ended?.winningTeam?.value ?: (ended?.reason as? EndReason.TeamVictory)?.winner?.value

        scalars["duration_s"] = seconds
        scalars["ended"] = if (ended != null) 1.0 else 0.0
        scalars["draw"] = if (ended != null && winner == null) 1.0 else 0.0
        scalars["players"] = playerList.size.toDouble()

        val ttff = firstFightTick?.let { (it - (start ?: it)) / 20.0 }
        scalars["ttff_s"] = ttff
        if (ttff == null) na["ttff_s"] = "no player-vs-player damage happened in this match"

        scalars["lead_changes"] = leadChanges.toDouble()
        val comeback: Double? = if (winner == null || leadSamples.isEmpty() || ticks == 0L) null else {
            val quarter = start!! + ticks * 3 / 4
            val before = leadSamples.lastOrNull { it.first <= quarter }?.second?.value
            if (before != null && before != winner) 1.0 else 0.0
        }
        scalars["comeback"] = comeback
        if (comeback == null) na["comeback"] = "needs a winner and at least one ${leadSampleTicks / 20}s score sample"

        scalars["stars_supplied"] = starsSupplied.toDouble()
        val perPlayerMinute = if (playerList.isNotEmpty() && minutes > 0) starsSupplied / (playerList.size * minutes) else null
        scalars["stars_per_player_minute"] = perPlayerMinute
        if (perPlayerMinute == null) na["stars_per_player_minute"] = "no players or zero duration"

        val idle = playerList.filter { it.activeTicks > 0 }.map { it.ticksByActivity[Activity.IDLE.ordinal].toDouble() / it.activeTicks }
        scalars["idle_ratio_mean"] = idle.takeIf { it.isNotEmpty() }?.average()
        scalars["idle_ratio_max"] = idle.maxOrNull()
        if (idle.isEmpty()) na["idle_ratio_mean"] = "no alive player ticks"
        na["idle_ratio_basis"] = "idle = no move / mine / fight; shopping is not observable in GameSim and counts as idle"
        val density = playerList.map { if (minutes > 0) it.transitions / minutes else 0.0 }
        scalars["decisions_per_min_mean"] = density.takeIf { it.isNotEmpty() && minutes > 0 }?.average()
        if (scalars["decisions_per_min_mean"] == null) na["decisions_per_min_mean"] = "no players or zero duration"
        if (decisionKindOf == null) na["decision_density_basis"] = "proxy: activity-class transitions per minute (no bot Dest.kind / Claude act seat attached)"

        scalars["kills_total"] = killTotal.toDouble()
        scalars["pillar_damage_total"] = pillarDamageTotal
        val kpm = if (minutes > 0) killTotal / minutes else null
        scalars["kills_per_min"] = kpm
        scalars["kills_per_min_per_player"] = if (minutes > 0 && playerList.isNotEmpty()) killTotal / minutes / playerList.size else null
        if (kpm == null) {
            na["kills_per_min"] = "zero duration"
            na["kills_per_min_per_player"] = "zero duration or no players"
        }
        if (roleOf == null) na["role_usefulness"] = "no RoleAssigner role source (bot seats not attached); grouped by team instead"
        na["tool_tier_contribution"] = "item tier is not exposed by GameSim; grouped by held item id instead (pillarDamageByItem, killsByItem)"

        val total = heat.sumOf { it.sum() }
        val unvisited = heat.sumOf { row -> row.count { it == 0 } } / (gridSize * gridSize).toDouble()
        scalars["heatmap_unvisited_ratio"] = if (bounds != null && total > 0) unvisited else null
        if (scalars["heatmap_unvisited_ratio"] == null) na["heatmap_unvisited_ratio"] = "stage bounds unknown or no player ticks"

        val mined = sim.oreNodes.mined
        if (mined.isEmpty()) {
            scalars["ore_contention"] = null
            scalars["ore_per_role"] = null
            na["ore_contention"] = "no ore node was mined"
            na["ore_per_role"] = "no ore node was mined"
        } else {
            val byCell = mined.groupBy { Triple(it.x, it.y, it.z) }
            val contested = byCell.values.count { events ->
                val teams = events.filter { it.miner != null }.map { it.tick to (sim.world.find(it.miner!!)?.team?.value ?: "?") }
                teams.any { (t, team) -> teams.any { (t2, team2) -> team != team2 && kotlin.math.abs(t - t2) <= contentionWindowTicks } }
            }
            scalars["ore_contention"] = contested.toDouble() / byCell.size
            na["ore_contention_basis"] = "share of mined node cells that two different teams mined within ${contentionWindowTicks / 20}s of each other (${byCell.size} cells, ${mined.size} minings)"
            val perPlayer = playerList.associate { it.id to 0 }.toMutableMap()
            for (m in mined) m.miner?.let { perPlayer.merge(it, m.items, Int::plus) }
            val groups = perPlayer.entries.groupBy({ group(it.key) }, { it.value })
            oreByGroup = groups.mapValues { (_, v) -> v.average() }
            scalars["ore_per_role"] = perPlayer.values.takeIf { it.isNotEmpty() }?.average()
            if (scalars["ore_per_role"] == null) na["ore_per_role"] = "no players"
            na["ore_per_role_basis"] = "mean ore items mined per player (detail per ${if (roleOf != null) "role" else "team, no RoleAssigner role source attached"} in metrics.json oreByGroup); miner = player the drops were delivered to"
        }

        val pick = pickupPositions
        if (pick.size >= 2) {
            val cells = pick.groupingBy { floor(it.x / 4.0).toLong() to floor(it.z / 4.0).toLong() }.eachCount()
            scalars["star_pickup_top_cell_share"] = cells.values.max().toDouble() / pick.size
            val cx = pick.map { it.x }.average()
            val cz = pick.map { it.z }.average()
            scalars["star_pickup_spread_m"] = sqrt(pick.map { (it.x - cx) * (it.x - cx) + (it.z - cz) * (it.z - cz) }.average())
        } else {
            scalars["star_pickup_top_cell_share"] = null
            scalars["star_pickup_spread_m"] = null
            val why = "fewer than 2 star pickups observed (${pick.size})"
            na["star_pickup_top_cell_share"] = why
            na["star_pickup_spread_m"] = why
        }

        val json = buildJsonObject {
            put("matchId", matchId?.value)
            put("stage", stageId?.value)
            put("startTick", start)
            put("endTick", endTick)
            put("durationSeconds", seconds)
            put("endReason", reason)
            put("winner", winner)
            put("ttffSeconds", ttff)
            put("killsTotal", killTotal)
            put("killsPerMinute", kpm)
            put("killsPerMinutePerPlayer", scalars["kills_per_min_per_player"])
            put("killsPerMinuteByTeam", JsonObject(killsByTeam.mapValues { JsonPrimitive(if (minutes > 0) it.value / minutes else 0.0) }))
            put("killsPerMinuteByPlayer", JsonObject(killsByPlayer.mapValues { JsonPrimitive(if (minutes > 0) it.value / minutes else 0.0) }))
            put("leadChanges", leadChanges)
            put("comeback", comeback)
            put("starsSupplied", starsSupplied)
            put("starsCollected", starsCollected)
            put("starsSuppliedPerPlayerMinute", perPlayerMinute)
            put("players", JsonObject(playerList.associate { st ->
                st.name to buildJsonObject {
                    put("team", teamOf[st.id]?.value)
                    put("idleRatio", if (st.activeTicks > 0) st.ticksByActivity[Activity.IDLE.ordinal].toDouble() / st.activeTicks else null)
                    put("decisionsPerMinute", if (minutes > 0) st.transitions / minutes else null)
                    put("ticksByActivity", JsonObject(Activity.entries.associate { it.name.lowercase() to JsonPrimitive(st.ticksByActivity[it.ordinal]) }))
                }
            }))
            put("oreByGroup", JsonObject(oreByGroup.mapValues { JsonPrimitive(it.value) }))
            put("oreNodesMined", mined.size)
            put("killsByGroup", JsonObject(kills.mapValues { JsonPrimitive(it.value) }))
            put("killsByItem", JsonObject(killsByItem.mapValues { JsonPrimitive(it.value) }))
            put("pillarDamageByGroup", JsonObject(pillarDamageByKey.mapValues { JsonPrimitive(it.value) }))
            put("pillarDamageByItem", JsonObject(pillarDamageByItem.mapValues { JsonPrimitive(it.value) }))
            put("heatmap", JsonArray(heat.map { row -> JsonArray(row.map { JsonPrimitive(it) }) }))
            put("heatmapUnvisitedRatio", scalars["heatmap_unvisited_ratio"])
            put("starPickupTopCellShare", scalars["star_pickup_top_cell_share"])
            put("starPickupSpreadMeters", scalars["star_pickup_spread_m"])
            put("leadSamples", JsonArray(leadSamples.map { (t, l, s) ->
                buildJsonObject {
                    put("tick", t)
                    put("leader", l?.value)
                    put("scores", JsonObject(s.mapValues { JsonPrimitive(it.value) }))
                }
            }))
            put("notAvailable", JsonObject(na.mapValues { JsonPrimitive(it.value) }))
        }
        return MatchMetricsResult(json, scalars, na)
    }
}
