package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemStock
import com.astarworks.astera.core.application.port.outbound.item.ItemOrigin
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.NexusDamaged
import com.astarworks.astera.core.domain.event.NexusDestroyed
import com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.SimEntityKind
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.event.StarsCollected
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
    /** Seconds from MatchStarted to each buyer's first match-shop purchase (pooled across runs by [MassPlay]). */
    val firstBuySeconds: List<Double> = emptyList(),
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
        /** Closest horizontal distance to the own team's shop NPC and the first tick within interact range (null = never). */
        var shopMinDistance: Double? = null
        var shopReachTick: Long? = null
        var lastPos: Vec3? = null
        var lastActivity: Activity? = null
        var lastDecision: String? = null
        var activeTicks = 0L
        val ticksByActivity = IntArray(Activity.entries.size)
        var transitions = 0
    }

    /** First successful match-shop buy of a player: absolute sim [tick], the item and the ore actually taken for it. */
    class FirstPurchase(val tick: Long, val item: String, val cost: Map<String, Int>)

    private val firstPurchase = HashMap<PlayerId, FirstPurchase>()
    private val pendingOre = HashMap<PlayerId, MutableMap<String, Int>>()
    private val buyAttempts = HashMap<PlayerId, Int>()
    private val bagAtFirstAttempt = HashMap<PlayerId, Map<String, Int>>()
    private val purchaseTaps = mutableListOf<ServiceRegistry.Registration>()

    /**
     * plugin:economy publishes no event for a match-shop buy, so the real purchase path is tapped through the two ports
     * `MatchShopPurchaseUseCase` resolves from the service registry on every call: ore taken out of the inventory
     * ([IOwnedItemStock.remove]) and the delivery with [ItemOrigin.EQUIP_IF_FREE], which only that use case uses. A buy counts
     * once the delivery succeeded (a refund or failed delivery never reaches that point); the ore taken since the player's
     * last delivery is the cost. The decorators are registered above the winner and removed in [finish].
     */
    private fun tapPurchasePath() {
        val services = sim.services
        val delivery = services.get<IItemDelivery>()
        val stock = services.get<IOwnedItemStock>()
        if (stock != null) purchaseTaps += services.register<IOwnedItemStock>(object : IOwnedItemStock by stock {
            override fun remove(playerId: PlayerId, itemId: ItemId, amount: Int): Boolean {
                // The first ore taken out of a bag for a purchase = one attempt (a refunded or failed delivery still counts; a buy that
                // fails the ore check before taking anything is not visible here). The bag is read before the removal.
                if (itemId.value.startsWith("ore-") && playerId !in pendingOre) {
                    buyAttempts.merge(playerId, 1, Int::plus)
                    if (playerId !in bagAtFirstAttempt) bagAtFirstAttempt[playerId] = oreBag(playerId)
                }
                val ok = stock.remove(playerId, itemId, amount)
                if (ok && itemId.value.startsWith("ore-")) pendingOre.getOrPut(playerId) { linkedMapOf() }.merge(itemId.value, amount, Int::plus)
                return ok
            }
        }, priority = TAP_PRIORITY)
        if (delivery != null) purchaseTaps += services.register<IItemDelivery>(object : IItemDelivery by delivery {
            override fun give(target: IMcPlayer, spec: ItemSpec, amount: Int, origin: ItemOrigin): DeliveryResult {
                val result = delivery.give(target, spec, amount, origin)
                val taken = pendingOre.remove(target.id)
                if (origin == ItemOrigin.EQUIP_IF_FREE && result !is DeliveryResult.UnknownTemplate) {
                    firstPurchase.putIfAbsent(target.id, FirstPurchase(sim.tick, spec.id.value, taken ?: emptyMap()))
                }
                return result
            }
        }, priority = TAP_PRIORITY)
    }

    private fun oreBag(id: PlayerId): Map<String, Int> =
        sim.server.view(id)?.givenItems?.filter { it.startsWith("ore-") }?.groupingBy { it }?.eachCount() ?: emptyMap()

    private fun trackShopReach(st: PlayerState, entity: SimEntity, team: TeamId) {
        val stg = stage ?: return
        val shop = stg.shopNpcs.firstOrNull { it.team == team } ?: return
        val dx = entity.position.x - (shop.x + stg.origin.x)
        val dz = entity.position.z - (shop.z + stg.origin.z)
        val d = sqrt(dx * dx + dz * dz)
        if (st.shopMinDistance == null || d < st.shopMinDistance!!) st.shopMinDistance = d
        if (st.shopReachTick == null && d <= SHOP_REACH && kotlin.math.abs(entity.position.y - (shop.y + stg.origin.y)) <= SHOP_REACH_Y) st.shopReachTick = lastTick
    }

    init { tapPurchasePath() }

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
    private var pillarTierOf: Map<PlayerId, Int> = emptyMap()
    private val destroyedPillars = HashSet<PlayerId>()
    // Per-team pillar accounting (team id value -> HP). Unattributed damage is in taken AND in the unattributed buckets, never in dealt.
    private val pillarDealtByTeam = HashMap<String, Double>()
    private val pillarTakenByTeam = HashMap<String, Double>()
    private val pillarTakenUnattributedByTeam = HashMap<String, Double>()
    private val pillarSelfInflictedByTeam = HashMap<String, Double>()
    private var pillarUnattributedTotal = 0.0
    private var bounds: DoubleArray? = null // minX, minZ, maxX, maxZ

    private val players = LinkedHashMap<PlayerId, PlayerState>()
    private val teamOf = HashMap<PlayerId, TeamId>()
    private var firstFightTick: Long? = null
    private var firstPillarHitTick: Long? = null
    private var firstPillarLossTick: Long? = null
    private val phaseTicks = LinkedHashMap<String, Long>()
    /** Ticks of cross-team engagements: a player-vs-player DamageDealt between different teams (any range), or a once-per-second sample with an alive cross-team pair within [ENGAGE_RADIUS] blocks. */
    private val engagementTicks = mutableListOf<Long>()
    private val pillarLosses = mutableListOf<Triple<Long, String, String>>()
    private val normaLevel = HashMap<PlayerId, Int>()

    /** Field tool / weapon tier tracking: a change = any of the four tiers differs from the previous 1 s sample. */
    class ToolChange(val tick: Long, val normaLevel: Int, val before: List<Int>, val after: List<Int>)
    private val normaUps = HashMap<PlayerId, MutableList<Pair<Long, Int>>>()
    private val toolNow = HashMap<PlayerId, List<Int>>()
    private val toolChanges = HashMap<PlayerId, MutableList<ToolChange>>()
    private val toolAtMinute = HashMap<PlayerId, MutableMap<Int, List<Int>>>()
    private val toolKinds = listOf("pickaxe", "axe", "shovel")
    private val toolTierNames = listOf("stone", "iron", "diamond")

    /** [pickaxe, axe, shovel, weapon] tiers: tools 0 none / 1 stone / 2 iron / 3 diamond, weapon = highest `-tN` given item (1 = base). */
    private fun toolTiers(id: PlayerId): List<Int>? {
        val stock = sim.services.get<IOwnedItemStock>() ?: return null
        val given = sim.server.view(id)?.givenItems ?: return null
        val tools = toolKinds.map { kind ->
            toolTierNames.indices.reversed().firstOrNull { stock.count(id, ItemId("${toolTierNames[it]}-$kind")) > 0 }?.plus(1) ?: 0
        }
        val weapon = given.mapNotNull { Regex("-t([2-9])$").find(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 1
        return tools + weapon
    }

    private fun sampleTools(tick: Long, started: Long) {
        val elapsed = tick - started
        for (st in players.values) {
            val now = toolTiers(st.id) ?: continue
            val prev = toolNow.put(st.id, now)
            if (prev != null && prev != now) toolChanges.getOrPut(st.id) { mutableListOf() } += ToolChange(tick, normaLevel[st.id] ?: 0, prev, now)
            if (elapsed > 0 && elapsed % 6_000L == 0L) toolAtMinute.getOrPut(st.id) { HashMap() }[(elapsed / 1_200).toInt()] = now
        }
    }

    private val kills = HashMap<String, Int>()
    private val killsByItem = HashMap<String, Int>()
    private var killTotal = 0
    private val killTicks = mutableListOf<Long>()
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
        val pillarEvents = mutableListOf<PillarHit>()
        for (e in fresh) when (e) {
            is MatchStarted -> if (matchId == null) onMatchStarted(e)
            is AnnihilationPhaseTransitioned -> if (e.matchId == matchId) phaseTicks.putIfAbsent(e.newPhase.id.toString(), e.atTick)
            is MatchEnded -> if (e.matchId == matchId && ended == null) { ended = e; endTick = e.atTick }
            is DamageDealt -> {
                val attacker = e.attacker
                // Pillar damage of every kind (weapon, tool skill, hand-break) is attributed from NexusDamaged / NexusDestroyed below.
                if (e.target !in pillarIds.keys && attacker != null && isPlayer(attacker) && isPlayer(e.target)) {
                    val ta = sim.world.find(attacker)?.team
                    val tt = sim.world.find(e.target)?.team
                    if (ta != null && tt != null && ta != tt) engagementTicks += e.atTick
                    fighters += attacker; fighters += e.target
                    if (firstFightTick == null) firstFightTick = e.atTick
                }
            }
            is BlockBroken -> miners += e.playerId
            is NexusDamaged -> if (e.matchId == matchId) {
                pillarEvents += PillarHit(e.owningTeam, (e.previousHp - e.currentHp).toDouble(), e.attackerId)
                e.attackerId?.let { pillarAttackers += it; pillarDirect.merge(it, e.damageAmount.toDouble(), Double::plus) }
            }
            is PlayerKilled -> onKill(e)
            is NexusDestroyed -> if (e.matchId == matchId) { if (firstPillarLossTick == null) firstPillarLossTick = tick; pillarLosses += Triple(tick, e.owningTeam.value, e.tier.toString()); destroyedPillars += AnnihilationPillarTargets.idOf("${e.matchId.value}-${e.owningTeam.value}-${e.tier.ordinalValue}"); pillarEvents += PillarHit(e.owningTeam, e.previousHp.toDouble(), e.attackerId); firstTierLossTickByTeam.putIfAbsent(e.owningTeam.value, tick); e.attackerId?.let { pillarAttackers += it; pillarDirect.merge(it, e.previousHp.toDouble(), Double::plus) } }
            is StarSupplied -> if (e.matchId == matchId) starsSupplied += e.amount
            is StarsCollected -> if (e.matchId == matchId) { starsCollected += e.amount; collected++ }
            is NormaLevelUp -> if (e.matchId == matchId) {
                normaLevel[e.playerId] = maxOf(normaLevel[e.playerId] ?: 0, e.level)
                normaUps.getOrPut(e.playerId) { mutableListOf() } += tick to e.level
            }
            else -> {}
        }
        val started = startTick ?: return
        if (endTick != null && tick > endTick!!) return
        trackPillars(pillarEvents)
        trackStars(collected)
        trackPlayers(fighters, miners)
        if (tick > started && (tick - started) % leadSampleTicks == 0L) sampleLead(tick)
        if (tick > started && (tick - started) % 20 == 0L) { sampleTools(tick, started); sampleProximity(tick) }
    }

    private fun sampleProximity(tick: Long) {
        val alive = sim.world.entities.filter { it.kind == SimEntityKind.PLAYER && it.team != null && sim.isAlive(it.id) }
        val r2 = ENGAGE_RADIUS * ENGAGE_RADIUS
        for (i in alive.indices) for (j in i + 1 until alive.size) {
            if (alive[i].team != alive[j].team && alive[i].position.distanceSquared(alive[j].position) <= r2) { engagementTicks += tick; return }
        }
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
            pillarTierOf = st.pillars.associate {
                AnnihilationPillarTargets.idOf("${e.matchId.value}-${it.team.value}-${it.tier.ordinalValue}") to it.tier.ordinalValue
            }
            val xs = st.pillars.map { it.position.x } + (st.layout?.islands?.map { it.x.toDouble() } ?: emptyList())
            val zs = st.pillars.map { it.position.z } + (st.layout?.islands?.map { it.z.toDouble() } ?: emptyList())
            if (xs.isNotEmpty()) bounds = doubleArrayOf(xs.min() - 24, zs.min() - 24, xs.max() + 24, zs.max() + 24)
        }
    }

    private fun group(id: PlayerId?): String =
        if (id == null) "environment" else roleOf?.invoke(id) ?: "team:${sim.world.find(id)?.team?.value ?: "?"}"

    /** A kill in phase 1 (build) whose victim (killer on another team) stood within [ENEMY_BASE_RADIUS] blocks (horizontal) of a pillar of the victim's own team. */
    class EnemyBaseKill(val tick: Long, val killer: String, val victim: String, val distance: Double)

    private val enemyBaseKills = mutableListOf<EnemyBaseKill>()

    private fun recordEnemyBaseKill(e: PlayerKilled) {
        val killerEntity = e.killer?.let { sim.world.find(it) } ?: return
        val victim = sim.world.find(e.victim) ?: return
        val mid = matchId ?: return
        val phase = sim.services.get<IMatchPhaseClock>()?.current(mid)?.phase
        if (phase != NexusPhase.Phase1Build) return
        val st = stage ?: return
        val killerTeam = killerEntity.team ?: return
        val victimTeam = victim.team ?: return
        if (killerTeam == victimTeam) return
        val d = st.pillars.filter { it.team == victimTeam }.minOfOrNull { p ->
            val dx = p.centre.x - victim.position.x
            val dz = p.centre.z - victim.position.z
            sqrt(dx * dx + dz * dz)
        } ?: return
        if (d <= ENEMY_BASE_RADIUS) enemyBaseKills += EnemyBaseKill(e.atTick, killerEntity.name, victim.name, d)
    }

    /** A kill whose victim stood inside its own team's spawn zone (centroid of the team's spawn points, spread + 12 blocks): spawn camping. */
    class SpawnZoneKill(val tick: Long, val killer: String, val victim: String, val distance: Double)

    private val spawnZoneKills = mutableListOf<SpawnZoneKill>()

    private fun recordSpawnZoneKill(e: PlayerKilled) {
        val killerEntity = e.killer?.let { sim.world.find(it) } ?: return
        val victim = sim.world.find(e.victim) ?: return
        val victimTeam = victim.team ?: return
        if (killerEntity.team == victimTeam) return
        val pts = stage?.spawnPoints?.filter { it.team == victimTeam } ?: return
        if (pts.isEmpty()) return
        val cx = pts.map { it.position.x }.average()
        val cz = pts.map { it.position.z }.average()
        val radius = pts.maxOf { sqrt((it.position.x - cx) * (it.position.x - cx) + (it.position.z - cz) * (it.position.z - cz)) } + 12.0
        val d = sqrt((victim.position.x - cx) * (victim.position.x - cx) + (victim.position.z - cz) * (victim.position.z - cz))
        if (d <= radius) spawnZoneKills += SpawnZoneKill(e.atTick, killerEntity.name, victim.name, d)
    }

    private fun onKill(e: PlayerKilled) {
        if (!isPlayer(e.victim)) return
        recordEnemyBaseKill(e)
        recordSpawnZoneKill(e)
        killTotal++
        killTicks += e.atTick
        kills.merge(group(e.killer), 1, Int::plus)
        e.killer?.let { k ->
            val ent = sim.world.find(k)
            killsByTeam.merge(ent?.team?.value ?: "?", 1, Int::plus)
            killsByPlayer.merge(ent?.name ?: "?", 1, Int::plus)
        }
        killsByItem.merge(e.killer?.let { sim.world.find(it)?.heldItem?.value } ?: "none", 1, Int::plus)
    }

    /** One pillar HP loss event (NexusDamaged / NexusDestroyed): the plugin's own record; attacker null = the Phase 5 bleed (its only null-attacker source). */
    private class PillarHit(val owner: TeamId, val amount: Double, val attacker: PlayerId?)

    private val pillarBleedByTeam = HashMap<String, Double>()
    private val pillarUnknownByTeam = HashMap<String, Double>()
    private var bleedApplications = 0
    private val firstTierLossTickByTeam = HashMap<String, Long>()

    /**
     * Attribution is event-driven: every HP loss the tracker publishes carries its attacker (player hit) or none (the bleed,
     * AnnihilationMatchTracker.applyBleedIfDue, the only caller that passes no attacker). The sampled HP delta is only a
     * cross-check: whatever it shows beyond the events is `unknown`. Dealt / taken / self-inflicted keep their meaning.
     */
    private fun trackPillars(events: List<PillarHit>) {
        val targets = sim.services.get<IStructureTargets>() ?: return
        val sampled = HashMap<String, Double>()
        for ((id, owner) in pillarIds) {
            // null = the match no longer tracks the pillar (ended/cleaned up): not damage, so the remaining HP must not count.
            val hp = targets.hitPoints(id) ?: continue
            val prev = pillarHp[id]
            if (prev != null && hp < prev) sampled.merge(owner.value, prev - hp, Double::plus)
            pillarHp[id] = hp
        }
        val eventSum = HashMap<String, Double>()
        var bleedThisTick = false
        for (ev in events) {
            val owner = ev.owner.value
            eventSum.merge(owner, ev.amount, Double::plus)
            pillarTakenByTeam.merge(owner, ev.amount, Double::plus)
            val pid = ev.attacker
            if (pid == null) {
                pillarBleedByTeam.merge(owner, ev.amount, Double::plus)
                bleedThisTick = true
                account(null, owner, ev.amount)
                continue
            }
            val attackerTeam = teamOf[pid] ?: sim.world.find(pid)?.team
            if (attackerTeam == null) { pillarUnknownByTeam.merge(owner, ev.amount, Double::plus); account(null, owner, ev.amount); continue }
            if (attackerTeam.value == owner) pillarSelfInflictedByTeam.merge(owner, ev.amount, Double::plus)
            else pillarDealtByTeam.merge(attackerTeam.value, ev.amount, Double::plus)
            account(pid, owner, ev.amount)
        }
        if (bleedThisTick) bleedApplications++
        for ((owner, d) in sampled) {
            val residual = d - (eventSum[owner] ?: 0.0)
            if (residual > 1e-9) {
                pillarTakenByTeam.merge(owner, residual, Double::plus)
                pillarUnknownByTeam.merge(owner, residual, Double::plus)
                account(null, owner, residual)
            }
        }
        val total = maxOf(sampled.values.sum(), eventSum.values.sum())
        if (total <= 0.0) return
        pillarDamageTotal += total
        if (firstPillarHitTick == null) firstPillarHitTick = lastTick
    }

    /** One slice of pillar damage: pid null goes to the unattributed bucket; both feed the per-group / per-item tallies. */
    private fun account(pid: PlayerId?, owner: String, amount: Double) {
        if (pid == null) {
            pillarUnattributedTotal += amount
            pillarTakenUnattributedByTeam.merge(owner, amount, Double::plus)
        }
        pillarDamageByKey.merge(if (pid == null) "unattributed" else group(pid), amount, Double::plus)
        pillarDamageByItem.merge(pid?.let { sim.world.find(it)?.heldItem?.value } ?: "unattributed", amount, Double::plus)
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
            if (startTick != null) trackShopReach(st, entity, team)
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
        purchaseTaps.forEach { it.unregister() }
        purchaseTaps.clear()
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
        // Normalised from the real win-condition result: TeamVictory = last team standing, PillarHpDecision = the sudden-death HP tiebreak.
        val endReasonKind = when (val r = ended?.reason) {
            null -> "running"
            is EndReason.TeamVictory -> if (r.winner != null) "elimination" else "draw"
            is EndReason.PillarHpDecision -> if (r.winner != null) "hp_decision" else "draw"
            else -> "other:${r::class.simpleName}"
        }
        val pillarTeams = (pillarIds.values + (stage?.pillars?.map { it.team } ?: emptyList())).map { it.value }.distinct().sorted()
        fun hpLeft(id: PlayerId): Double? = if (id in destroyedPillars) 0.0 else pillarHp[id]
        val hpLeftByTeam = pillarTeams.associateWith { t -> pillarIds.filterValues { it.value == t }.keys.sumOf { hpLeft(it) ?: 0.0 } }

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

        val buyerSeconds = playerList.mapNotNull { st -> firstPurchase[st.id]?.let { (it.tick - (start ?: it.tick)) / 20.0 } }.sorted()
        scalars["first_buy_median_s"] = medianOf(buyerSeconds)
        scalars["never_bought"] = playerList.count { it.id !in firstPurchase }.toDouble()
        if (buyerSeconds.isEmpty()) na["first_buy_median_s"] = "no player completed a match-shop purchase"

        scalars["p1_kills_in_enemy_base"] = enemyBaseKills.size.toDouble()

        scalars["kills_total"] = killTotal.toDouble()

        // Phase windows [start, next start) (the last one includes the match end tick). P4+ = Phase4Escalation + Phase5SuddenDeath.
        // A phase the match never reached has zero minutes and zero kills, so pooling over matches stays a plain sum / sum.
        val p1Start = start
        val p2Start = phaseTicks[NexusPhase.Phase2Engage.id]
        val p3Start = phaseTicks[NexusPhase.Phase3Center.id]
        val p4Start = phaseTicks[NexusPhase.Phase4Escalation.id] ?: phaseTicks[NexusPhase.Phase5SuddenDeath.id]
        val bounds4 = listOf(p1Start, p2Start, p3Start, p4Start).map { t -> t?.coerceAtMost(end) }
        val phaseNames = listOf("p1", "p2", "p3", "p4plus")
        var p2KillsInPhase = emptyList<Long>()
        for (i in 0 until 4) {
            val from = bounds4[i]
            val to = (i + 1 until 4).firstNotNullOfOrNull { bounds4[it] } ?: end
            val inPhase = if (from == null) emptyList() else killTicks.filter { it >= from && (it < to || (to == end && it == end)) }
            if (i == 1) p2KillsInPhase = inPhase
            scalars["kills_${phaseNames[i]}"] = inPhase.size.toDouble()
            scalars["minutes_${phaseNames[i]}"] = if (from == null) 0.0 else (to - from).coerceAtLeast(0L) / 1200.0
        }
        val p2Ttff = if (p2Start == null) null else p2KillsInPhase.minOrNull()?.let { (it - p2Start) / 20.0 }
        scalars["p2_ttff_s"] = p2Ttff
        if (p2Start == null) na["p2_ttff_s"] = "the match never reached phase 2 (engage)"
        else if (p2Ttff == null) na["p2_ttff_s"] = "no kill happened during phase 2 (engage)"

        // Stall signature: maximal gaps of at least STALL_WINDOW_TICKS between cross-team engagements inside P3+ (P3 start .. match end).
        val stallWindows = if (p3Start == null) 0 else {
            val pts = (listOf(p3Start) + engagementTicks.filter { it >= p3Start && it <= end }.sorted() + listOf(end))
            pts.zipWithNext().count { (a, b) -> b - a >= STALL_WINDOW_TICKS }
        }
        scalars["stall_windows_p3plus"] = stallWindows.toDouble()
        scalars["pillar_damage_total"] = pillarDamageTotal
        scalars["pillar_damage_unattributed"] = pillarUnattributedTotal
        scalars["pillar_damage_bleed"] = pillarBleedByTeam.values.sum()
        scalars["pillar_damage_unknown"] = pillarUnknownByTeam.values.sum()
        scalars["bleed_applications"] = bleedApplications.toDouble()
        for (t in pillarTeams) {
            scalars["pillar_damage_dealt_$t"] = pillarDealtByTeam[t] ?: 0.0
            scalars["pillar_damage_taken_$t"] = pillarTakenByTeam[t] ?: 0.0
            scalars["pillar_hp_left_$t"] = hpLeftByTeam[t]
        }
        scalars["first_pillar_hit_s"] = firstPillarHitTick?.let { (it - (start ?: it)) / 20.0 }
        scalars["first_pillar_loss_s"] = firstPillarLossTick?.let { (it - (start ?: it)) / 20.0 }
        if (firstPillarHitTick == null) na["first_pillar_hit_s"] = "no pillar took damage"
        if (firstPillarLossTick == null) na["first_pillar_loss_s"] = "no pillar fell"
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
            put("end_reason", endReasonKind)
            put("end_decided_by", (ended?.reason as? EndReason.PillarHpDecision)?.decidedBy?.name)
            // Per team, from the decision itself; null unless the clock decided (empty map) or the end was not a PillarHpDecision.
            put("end_play_hp", (ended?.reason as? EndReason.PillarHpDecision)?.playHp?.takeIf { it.isNotEmpty() }?.let { m -> JsonObject(m.entries.associate { it.key.value to JsonPrimitive(it.value) }) } ?: kotlinx.serialization.json.JsonNull)
            put("end_bleed_taken", (ended?.reason as? EndReason.PillarHpDecision)?.bleedTaken?.takeIf { it.isNotEmpty() }?.let { m -> JsonObject(m.entries.associate { it.key.value to JsonPrimitive(it.value) }) } ?: kotlinx.serialization.json.JsonNull)
            put("pillar_damage_unattributed", pillarUnattributedTotal)
            put("pillar_damage_bleed", pillarBleedByTeam.values.sum())
            put("pillar_damage_unknown", pillarUnknownByTeam.values.sum())
            put("bleed_applications", bleedApplications)
            put("teams", JsonObject(pillarTeams.associateWith { t ->
                buildJsonObject {
                    put("pillar_damage_dealt", pillarDealtByTeam[t] ?: 0.0)
                    put("pillar_damage_taken", pillarTakenByTeam[t] ?: 0.0)
                    put("pillar_damage_taken_unattributed", pillarTakenUnattributedByTeam[t] ?: 0.0)
                    put("pillar_damage_self_inflicted", pillarSelfInflictedByTeam[t] ?: 0.0)
                    put("pillar_damage_bleed", pillarBleedByTeam[t] ?: 0.0)
                    put("pillar_damage_taken_by_players", (pillarTakenByTeam[t] ?: 0.0) - (pillarBleedByTeam[t] ?: 0.0))
                    put("pillar_damage_unknown", pillarUnknownByTeam[t] ?: 0.0)
                    put("first_tier_loss_min", firstTierLossTickByTeam[t]?.let { (it - (start ?: it)) / 1200.0 })
                    put("pillar_hp_left", buildJsonObject {
                        put("sum", hpLeftByTeam[t])
                        val own = pillarIds.filterValues { it.value == t }.keys
                        put("by_tier", JsonObject(own.sortedBy { pillarTierOf[it] ?: 0 }.associate { "tier${pillarTierOf[it]}" to JsonPrimitive(hpLeft(it)) }))
                    })
                }
            }))
            put("winner", winner)
            put("ttffSeconds", ttff)
            put("firstPillarHitSeconds", scalars["first_pillar_hit_s"])
            put("firstPillarLossSeconds", scalars["first_pillar_loss_s"])
            put("pillarLosses", JsonArray(pillarLosses.map { JsonPrimitive("${(it.first - (start ?: it.first)) / 20.0}s ${it.second} ${it.third}") }))
            put("phaseStartSeconds", JsonObject(phaseTicks.mapValues { JsonPrimitive(((it.value - (start ?: it.value)) / 20.0)) }))
            put("killsTotal", killTotal)
            put("p2TtffSeconds", p2Ttff)
            put("killsByPhase", JsonObject(phaseNames.associateWith { JsonPrimitive(scalars["kills_$it"]) }))
            put("minutesByPhase", JsonObject(phaseNames.associateWith { JsonPrimitive(scalars["minutes_$it"]) }))
            put("stallWindowsP3Plus", stallWindows)
            put("killsPerMinute", kpm)
            put("killsPerMinutePerPlayer", scalars["kills_per_min_per_player"])
            put("killsPerMinuteByTeam", JsonObject(killsByTeam.mapValues { JsonPrimitive(if (minutes > 0) it.value / minutes else 0.0) }))
            put("killsPerMinuteByPlayer", JsonObject(killsByPlayer.mapValues { JsonPrimitive(if (minutes > 0) it.value / minutes else 0.0) }))
            put("leadChanges", leadChanges)
            put("comeback", comeback)
            put("starsSupplied", starsSupplied)
            put("starsCollected", starsCollected)
            put("starsSuppliedPerPlayerMinute", perPlayerMinute)
            put("p1KillsInEnemyBase", enemyBaseKills.size)
            put("p1KillsInEnemyBaseList", JsonArray(enemyBaseKills.map { k ->
                buildJsonObject {
                    put("tick", k.tick)
                    put("killer", k.killer)
                    put("victim", k.victim)
                    put("distance", k.distance)
                }
            }))
            put("killsInVictimSpawnZone", spawnZoneKills.size)
            put("killsInVictimSpawnZoneList", JsonArray(spawnZoneKills.map { k ->
                buildJsonObject {
                    put("tick", k.tick)
                    put("killer", k.killer)
                    put("victim", k.victim)
                    put("distance", k.distance)
                }
            }))
            put("firstBuyMedianSeconds", scalars["first_buy_median_s"])
            put("neverBought", scalars["never_bought"]?.toInt())
            put("players", JsonObject(playerList.associate { st ->
                st.name to buildJsonObject {
                    val fp = firstPurchase[st.id]
                    put("shop_reach", buildJsonObject {
                        put("min_distance", st.shopMinDistance)
                        put("first_in_range_tick", st.shopReachTick)
                    })
                    put("buy_attempts", buildJsonObject {
                        put("count", buyAttempts[st.id] ?: 0)
                        put("bag_ore_at_first_attempt", bagAtFirstAttempt[st.id]?.let { b -> JsonObject(b.mapValues { JsonPrimitive(it.value) }) } ?: JsonNull)
                    })
                    put("first_purchase", if (fp == null) JsonNull else buildJsonObject {
                        put("tick", fp.tick)
                        put("seconds", (fp.tick - (start ?: fp.tick)) / 20.0)
                        put("item", fp.item)
                        put("cost", JsonObject(fp.cost.mapValues { JsonPrimitive(it.value) }))
                    })
                    put("team", teamOf[st.id]?.value)
                    put("toolTiers", buildJsonObject {
                        put("order", "pickaxe,axe,shovel,weapon")
                        put("normaLevelFinal", normaLevel[st.id] ?: 0)
                        put("normaUps", JsonArray((normaUps[st.id] ?: emptyList()).map { (t, l) -> buildJsonObject { put("tick", t); put("level", l) } }))
                        put("atMinute", JsonObject((toolAtMinute[st.id] ?: emptyMap()).entries.sortedBy { it.key }.associate { it.key.toString() to JsonArray(it.value.map { v -> JsonPrimitive(v) }) }))
                        put("changes", JsonArray((toolChanges[st.id] ?: emptyList()).map { c ->
                            buildJsonObject {
                                put("tick", c.tick); put("normaLevel", c.normaLevel)
                                put("before", JsonArray(c.before.map { JsonPrimitive(it) })); put("after", JsonArray(c.after.map { JsonPrimitive(it) }))
                            }
                        }))
                    })
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
        return MatchMetricsResult(json, scalars, na, buyerSeconds)
    }
}

private const val TAP_PRIORITY = 1_000

/** Interact range of the base shop, same as `MatchShopBotBuyer`. */
private const val SHOP_REACH = 5.0
private const val SHOP_REACH_Y = 3.0

/** Phase-1 base-kill radius, blocks (horizontal). */
private const val ENEMY_BASE_RADIUS = 35.0

private fun medianOf(sorted: List<Double>): Double? = when {
    sorted.isEmpty() -> null
    sorted.size % 2 == 1 -> sorted[sorted.size / 2]
    else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
}

/** A cross-team engagement for the stall signature: alive players of different teams this close (blocks), or any cross-team damage. */
internal const val ENGAGE_RADIUS = 12.0

/** A stall window: at least 3 minutes (3600 ticks) in P3+ with no cross-team engagement. */
internal const val STALL_WINDOW_TICKS = 3_600L
