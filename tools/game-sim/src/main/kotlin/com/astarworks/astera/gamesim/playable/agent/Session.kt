package com.astarworks.astera.gamesim.playable.agent

import com.astarworks.astera.bots.Navigator
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchLevelGate
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.economy.application.service.MatchShopLoaderService
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.SimEntityKind
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.NormaLevelService
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.event.NormaCheckReady
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarsCollected
import java.io.File
import kotlin.math.floor
import kotlin.math.hypot

/** What one `act` / `skip` did: [stopped] is the reason it returned, [note] the human-readable outcome. */
data class StepOutcome(val ok: Boolean, val stopped: String, val note: String, val shop: List<ShopLine>? = null, val failed: Boolean = false)

/** One shop offer as the seat sees it: the real price (ore name -> count), what it still lacks and whether the norma level locks it. */
data class ShopLine(
    val id: String, val tab: String, val price: Map<String, Int>, val missing: Map<String, Int>, val lockedUntilLevel: Int?, val needsBase: String?,
) {
    val affordable: Boolean get() = missing.isEmpty() && needsBase == null
    val priceText: String get() = price.entries.joinToString("+") { "${it.value} ${it.key} ore" }
}

/** A tick and the seconds it is into the match (20 ticks/s). */
data class Moment(val tick: Long) { val seconds: Double get() = tick / 20.0 }

/**
 * One agent-controlled seat of one match (ADR-0316): the action vocabulary, the stop conditions, the event digest and
 * the observation. All game state is read from / driven through the [MatchHost]; nothing is mocked.
 */
class Session(private val host: MatchHost, contentDir: File) {
    private val sim: GameSim get() = host.sim
    private val me: SimEntity = host.me()
    private val team: String = me.team?.value ?: error("the agent seat has no team")
    private val objectives: StageObjectives = StageObjectives.load(contentDir, host.request.mode)
        ?: error("no stage objectives for lane '${host.request.mode}'")
    private val stage = sim.services.getOrThrow<IStageRegistry>().find(StageId(host.stageId)) ?: error("stage ${host.stageId} missing")
    private val origin = stage.origin
    private val plan: ArenaBuildPlan = host.terrain.plan
    private val terrain = host.terrain.view
    private val navHost = host.meHost()
    private val navigator = Navigator(terrain, objectives.routeGraph())
    private val catalog = MatchShopLoaderService().also { it.loadFrom(contentDir.toPath().resolve("shops")) }.catalog
    private val stars = sim.services.getOrThrow<IMatchStarStore>()
    private val normaStore = sim.services.getOrThrow<IMatchNormaStore>()
    private val normaService = sim.services.get<NormaLevelService>()
    private val levelGate = sim.services.get<IMatchLevelGate>()
    private val itemRegistry = sim.services.get<IItemRegistry>()
    private val ledger = sim.services.getOrThrow<ICurrencyLedger>()
    private val worldId = sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(host.matchId)?.worldId ?: me.world
    private val baseCentres: Map<String, Triple<Double, Double, Double>> = stage.spawnPoints
        .filter { it.role == SpawnRole.TEAM && it.team != null }
        .groupBy { it.team!!.value }
        .mapValues { (_, pts) -> Triple(pts.map { it.position.x }.average(), pts.map { it.position.y }.average(), pts.map { it.position.z }.average()) }
    val pois: PoiIndex = PoiIndex.build(objectives, baseCentres)
    private val maxHp = HashMap<PlayerId, Double>()

    // --- event digest ---------------------------------------------------------------------------------------------
    private var pumpCursor = sim.events.size
    private val pending = ArrayList<String>()
    private var phase = "build"
    private var ended = false
    private var lastHp = hp()
    private var arrivedFlag = false
    private var diedFlag = false
    /** A death the agent has not been told about through a `died` stop yet. */
    private var deathUnreported = false
    /** Tick the seat died at while it waits for the match's respawn timer; null while alive. */
    private var deadSince: Long? = null
    private var readySeen = false
    private var levelSeen = normaLevel()
    private var lastMessageCount = messages().size

    // --- stats for the reflection at `end` ------------------------------------------------------------------------
    var decisions = 0; private set
    var skippedTicks = 0L; private set
    var longestWait = 0L; private set
    var longestWaitAt = ""; private set
    var emptyMenus = 0; private set
    var starsGained = 0; private set
    var oreMined = 0; private set
    var purchases = 0; private set
    var kills = 0; private set
    var deaths = 0; private set
    val starMoments = ArrayList<String>()
    var decisionsFailed = 0; private set
    val failures = ArrayList<String>()
    var firstStar: Moment? = null; private set
    var firstKill: Moment? = null; private set
    var firstNormaLevelUp: Moment? = null; private set

    init {
        host.step(1)
        pump()
    }

    // --- reads ----------------------------------------------------------------------------------------------------
    private fun messages() = sim.server.view(me.id)?.messages ?: mutableListOf()
    private fun inventory(): Map<String, Int> = (sim.server.view(me.id)?.givenItems ?: emptyList()).groupingBy { it }.eachCount()
    private fun hp(): Double = sim.hp(me) ?: 0.0
    private fun normaLevel(): Int = normaStore.get(host.matchId, me.id).level
    private fun starCount(): Int = stars.get(host.matchId, me.id)
    private fun oreTotal(): Int = inventory().filterKeys { it.startsWith("ore-") }.values.sum()
    private fun name(id: PlayerId?): String = id?.let { sim.world.find(it)?.name } ?: "?"

    private fun others(): List<SimEntity> = sim.world.entities.filter { it.id != me.id && sim.isAlive(it.id) }

    private fun hostile(e: SimEntity) = e.team?.value != team

    private fun nearestEnemyDistance(): Double =
        others().filter(::hostile).minOfOrNull { hypot(it.position.x - me.position.x, it.position.z - me.position.z) } ?: Double.POSITIVE_INFINITY

    private fun hpBand(e: SimEntity): String {
        val cur = sim.hp(e) ?: return "?"
        val max = maxHp.getOrPut(e.id) { cur.coerceAtLeast(1.0) }.coerceAtLeast(cur)
        return when {
            cur >= max * 0.9 -> "full"
            cur >= max * 0.45 -> "half"
            else -> "low"
        }
    }

    private val probe: UntilProbe = object : UntilProbe {
        override fun phase(): String = phase
        override fun hp(): Double = hp()
        override fun nearestEnemy() = nearestEnemyDistance()
        override fun stars() = starCount()
        override fun ore() = oreTotal()
        override fun norma() = normaLevel()
        override fun tick() = host.tick
        override fun arrived() = arrivedFlag
        override fun died() = diedFlag
    }

    fun onNewDecision() { decisions++ }

    // --- event pump -----------------------------------------------------------------------------------------------
    /** Digests the domain events published since the last call into the agent-facing digest. */
    private fun pump() {
        val events: List<DomainEvent> = sim.events
        while (pumpCursor < events.size) {
            when (val e = events[pumpCursor++]) {
                is DamageDealt -> when {
                    e.target == me.id -> pending += "hit by ${name(e.attacker)} -${"%.0f".format(e.resolved.finalAmount)}"
                    e.attacker == me.id -> pending += "hit ${name(e.target)} -${"%.0f".format(e.resolved.finalAmount)}"
                }
                is PlayerKilled -> when {
                    e.victim == me.id -> { deaths++; deadSince = host.tick; deathUnreported = true; pending += "died (${name(e.killer)}), respawn in ~$RESPAWN_TICKS ticks" }
                    e.killer == me.id -> { kills++; if (firstKill == null) firstKill = Moment(host.tick); pending += "killed ${name(e.victim)}" }
                }
                is PlayerRespawned -> if (e.playerId == me.id) { deadSince = null; pending += "respawned" }
                is StarsCollected -> if (e.playerId == me.id) {
                    starsGained += e.amount
                    if (firstStar == null) firstStar = Moment(host.tick)
                    pending += "star +${e.amount} (${e.total})"
                    starMoments += "t=${host.tick} star +${e.amount} total ${e.total}"
                }
                is NormaCheckReady -> if (e.playerId == me.id) { readySeen = true; pending += "norma check ready for lv${e.level}: go home, norma" }
                is NormaLevelUp -> if (e.playerId == me.id) { if (firstNormaLevelUp == null) firstNormaLevelUp = Moment(host.tick); pending += "norma level ${e.level}"; starMoments += "t=${host.tick} norma lv${e.level}" }
                is AnnihilationPhaseTransitioned -> if (e.matchId == host.matchId) { phase = e.newPhase.id; pending += "phase ${e.newPhase.id}" }
                is MatchEnded -> if (e.matchId == host.matchId) { ended = true; pending += "match ended, winner ${e.winningTeam?.value ?: "none"}" }
                else -> {}
            }
        }
        val msgs = messages()
        while (lastMessageCount < msgs.size) pending += "msg: ${msgs[lastMessageCount++].take(80)}"
    }

    // --- tick loop ------------------------------------------------------------------------------------------------
    /**
     * Runs up to [max] ticks. [onTick] runs before each tick and returns a stop reason to finish (null = keep going).
     * Afterwards the stop conditions are checked: the caller's [until], then (unless [interrupts] is false) the default
     * interrupts: died, hit taken, enemy comes within 12, phase change, norma level / check ready, match end.
     */
    private fun run(max: Int, until: UntilDsl?, interrupts: Boolean, onTick: () -> String?): String {
        val startPhase = phase
        val startLevel = normaLevel()
        var enemyNear = nearestEnemyDistance() <= 12.0
        readySeen = false
        lastHp = hp()
        var used = 0
        diedFlag = false
        pump()
        var wasAlive = sim.isAlive(me.id)
        if (!wasAlive && deathUnreported) { deathUnreported = false; diedFlag = true; return "died" }
        while (used < max) {
            if (wasAlive) onTick()?.let { return it }
            host.step(1)
            used++
            pump()
            if (ended || host.ended) return "match_ended"
            val alive = sim.isAlive(me.id)
            if (wasAlive && !alive) { diedFlag = true; deathUnreported = false; return "died" }
            if (!alive) continue // dead: the match's respawn timer brings the seat back, keep waiting
            if (!wasAlive) { wasAlive = true; lastHp = hp(); enemyNear = nearestEnemyDistance() <= 12.0; navigator.clear() }
            until?.fired(probe)?.let { return "until:$it" }
            if (!interrupts) continue
            val hpNow = hp()
            if (hpNow < lastHp - 1e-6) return "hit_taken"
            lastHp = hpNow
            val near = nearestEnemyDistance() <= 12.0
            if (near && !enemyNear) return "enemy_within_12"
            enemyNear = near
            if (phase != startPhase) return "phase_change"
            if (normaLevel() > startLevel || readySeen) return "norma"
        }
        return "timeout"
    }

    // --- stepping ops ---------------------------------------------------------------------------------------------
    fun skip(untilText: String?, max: Int, interrupts: Boolean): StepOutcome = track("skip ${untilText ?: ""}") { skipInner(untilText, max, interrupts) }

    fun act(text: String, untilText: String?, max: Int, interrupts: Boolean): StepOutcome = track(text) { actInner(text, untilText, max, interrupts) }

    /** A failed decision: what ran, the stop reason and the note, for the end summary and the timeline. */
    data class Failure(val tick: Long, val label: String, val stopped: String, val note: String) {
        fun line() = "t=$tick '$label': stopped=$stopped (${note.take(100)})"
    }
    val failureLog = ArrayList<Failure>()
    private var lastFailure: Failure? = null

    /** Counts a decision that failed for a reason of its own (bad target, refusal, ...), not one an interrupt or a timeout ended. */
    private fun track(label: String, body: () -> StepOutcome): StepOutcome {
        val startTick = host.tick
        val after = lastFailure?.takeIf { it.tick == startTick }
        lastFailure = null
        var out = body()
        if (after != null) out = out.copy(note = "[ran right after failed '${after.label}' (${after.stopped}) at the same tick t=$startTick] ${out.note}")
        if (!out.ok && out.stopped !in SOFT_STOPS && !out.stopped.startsWith("until:")) {
            decisionsFailed++
            val f = Failure(host.tick, label, out.stopped, out.note)
            failureLog += f
            failures += f.line()
            lastFailure = f
            out = out.copy(failed = true)
        }
        return out
    }

    private fun skipInner(untilText: String?, max: Int, interrupts: Boolean): StepOutcome {
        val until = untilText?.let { UntilDsl.parse(it).getOrElse { e -> return StepOutcome(false, "bad_until", e.message ?: "bad until") } }
        arrivedFlag = false
        val t0 = host.tick
        val why = run(max, until, interrupts) { null }
        recordWait(host.tick - t0, "skip ${untilText ?: ""}")
        return StepOutcome(true, why, "skipped ${host.tick - t0} ticks")
    }

    private fun recordWait(ticks: Long, label: String) {
        skippedTicks += ticks
        if (ticks > longestWait) { longestWait = ticks; longestWaitAt = "t=${host.tick - ticks}..${host.tick} $label" }
    }

    private fun actInner(text: String, untilText: String?, max: Int, interrupts: Boolean): StepOutcome {
        val until = untilText?.let { UntilDsl.parse(it).getOrElse { e -> return StepOutcome(false, "bad_until", e.message ?: "bad until") } }
        val verb = text.trim().substringBefore(' ')
        val arg = text.trim().substringAfter(' ', "").trim()
        arrivedFlag = false
        return when (verb) {
            "goto" -> goto(arg, until, max, interrupts, arrive = null)
            "dig" -> dig(arg, until, max, interrupts)
            "attack" -> attack(arg, until, max, interrupts)
            "cast" -> cast(arg)
            "buy" -> buy(arg, until, max, interrupts)
            "norma" -> norma(arg, until, max, interrupts)
            "hold" -> hold(arg)
            "wait" -> {
                val n = arg.toIntOrNull()?.coerceIn(1, 6000) ?: return StepOutcome(false, "bad_arg", "wait <ticks>")
                val t0 = host.tick
                val why = run(n, until, interrupts) { null }
                recordWait(host.tick - t0, "wait")
                StepOutcome(true, if (why == "timeout") "done" else why, "waited ${host.tick - t0} ticks")
            }
            else -> StepOutcome(false, "unknown_action", "unknown action '$verb' (goto dig attack cast buy norma hold wait)")
        }
    }

    // --- actions --------------------------------------------------------------------------------------------------
    private fun resolvePoi(arg: String): Poi? {
        val star = if (arg == "star") sim.starDrops.lying().minByOrNull { hypot(it.position.x - me.position.x, it.position.z - me.position.z) } else null
        if (star != null) return Poi("star", "star", star.position.x, star.position.y, star.position.z)
        return pois.resolve(arg, team, me.position.x, me.position.z)
    }

    private fun goto(arg: String, until: UntilDsl?, max: Int, interrupts: Boolean, arrive: Double?, target: Poi? = null): StepOutcome {
        var budget = max
        var poi = target ?: resolvePoi(arg)
        if (poi == null && arg == "star" && until?.source?.split('|')?.any { it.trim() == "arrived" } == true) {
            val t0 = host.tick
            val waited = run(max, until, interrupts) { if (sim.starDrops.lying().isNotEmpty()) "star_spawned" else null }
            budget = (max - (host.tick - t0).toInt()).coerceAtLeast(1)
            if (waited != "star_spawned") return StepOutcome(false, waited, "no star spawned after waiting ${host.tick - t0} ticks")
            poi = resolvePoi(arg)
        }
        if (poi == null) return StepOutcome(false, "unknown_target", "no place '$arg' (see menu / look)")
        val radius = arrive ?: if (poi.kind == "xyz") 1.0 else if (poi.kind == "star") 0.8 else 1.6
        var noPath = 0
        navigator.clear()
        val why = run(budget, until, interrupts) {
            val gy = poi.y.takeIf { poi.kind == "xyz" && !it.isNaN() }
            when (navigator.follow(navHost, poi.x, poi.z, gy, radius, SPEED, host.tick * 50L, null, 0f)) {
                Navigator.Status.ARRIVED -> "arrived"
                Navigator.Status.NO_PATH -> if (++noPath > 40) "no_path" else null
                Navigator.Status.MOVING -> { noPath = 0; null }
            }
        }
        if (why == "arrived") arrivedFlag = true
        val d = hypot(poi.x - me.position.x, poi.z - me.position.z)
        return StepOutcome(why == "arrived", why, if (why == "arrived") "at ${poi.name}" else "${"%.0f".format(d)} blocks short of ${poi.name}")
    }

    private fun inventoryTool(kind: String): String? {
        val tiers = listOf("diamond", "iron", "stone", "wooden", "wood", "gold")
        val owned = inventory().keys
        return tiers.map { "$it-$kind" }.firstOrNull { it in owned }
    }

    private fun toolFor(ore: String): String? = when {
        ore.contains("wood") || ore.contains("timber") -> inventoryTool("axe")
        ore.contains("clay") || ore.contains("silt") -> inventoryTool("shovel")
        else -> inventoryTool("pickaxe")
    } ?: inventoryTool("pickaxe")

    /** Block types seen on a node cell right after it was mined: a cell showing one of them is regrowing. */
    private val placeholders = HashSet<String>()

    private fun nodeCell(poi: Poi): Vec3 = Vec3(floor(poi.x), floor(if (poi.y.isNaN()) me.position.y else poi.y), floor(poi.z))

    private fun nodeReady(poi: Poi): Boolean = sim.world.blockAt(me.world, nodeCell(poi))?.key !in placeholders

    private fun learnPlaceholder(poi: Poi) { sim.world.blockAt(me.world, nodeCell(poi))?.key?.let { placeholders += it } }

    /**
     * `dig`: one node, as ever, unless an `until` is given on a Mid ore: then it keeps digging the nearest ready node of that
     * ore (or waits for one to regrow) until the condition holds or [max] ticks are used.
     */
    private fun dig(arg: String, until: UntilDsl?, max: Int, interrupts: Boolean): StepOutcome {
        val first = resolvePoi(arg) ?: return StepOutcome(false, "unknown_target", "no place '$arg'")
        if (until == null || first.kind != "mid-ore") return digOne(first, arg, until, max, interrupts)
        val family = pois.ofKind("mid-ore").filter { it.ore == first.ore }
        val exact = pois.find(arg.trim())
        val deadline = host.tick + max
        var mined = 0
        val before = oreTotal()
        fun summary(tail: String) = "$tail; mined $mined node${if (mined == 1) "" else "s"}, ore ${before} -> ${oreTotal()}"
        while (true) {
            val remaining = (deadline - host.tick).toInt()
            if (remaining <= 0) return StepOutcome(false, "timeout", summary("max ticks used"))
            val ready = family.filter(::nodeReady)
            val target = (exact?.takeIf { it in ready } ?: ready.minByOrNull { it.distanceTo(me.position.x, me.position.z) })
            if (target == null) {
                val why = run(minOf(remaining, REGROW_POLL), until, interrupts) { null }
                if (why != "timeout") return StepOutcome(why.startsWith("until:"), why, summary("waited for regrow"))
                continue
            }
            val out = digOne(target, target.name, until, remaining, interrupts)
            when (out.stopped) {
                "mined" -> { mined++; until.fired(probe)?.let { return StepOutcome(true, "until:$it", summary("until holds")) } }
                "regrowing" -> run(1, until, interrupts) { null }.let { w -> if (w != "timeout") return StepOutcome(w.startsWith("until:"), w, summary("node regrowing")) }
                else -> return out.copy(note = summary(out.note))
            }
        }
    }

    private fun digOne(poi: Poi, arg: String, until: UntilDsl?, max: Int, interrupts: Boolean): StepOutcome {
        val y = if (poi.y.isNaN()) me.position.y else poi.y
        val cell = Vec3(floor(poi.x), floor(y), floor(poi.z))
        if (hypot(poi.x - me.position.x, poi.z - me.position.z) > REACH) {
            val walk = goto(arg, until, max, interrupts, arrive = REACH - 1.0, target = poi)
            if (!walk.ok) return walk
        }
        val before = oreTotal()
        var nextSwing = host.tick
        var result = ""
        val why = run(max, until, interrupts) {
            if (host.tick < nextSwing) return@run null
            nextSwing = host.tick + HIT_COOLDOWN
            val tool = toolFor(poi.ore)
            when (val out = sim.mineNode(me, worldId, cell, tool?.let(::ItemId))) {
                is OreNodeService.Outcome.Hit -> { result = "hit ${out.hp}/${out.maxHp}"; null }
                OreNodeService.Outcome.Cooldown -> null
                is OreNodeService.Outcome.Mined -> { result = "mined"; learnPlaceholder(poi); "mined" }
                OreNodeService.Outcome.WrongTool -> { result = "wrong tool class (${tool ?: "no tool"})"; "too_weak" }
                OreNodeService.Outcome.TooWeak -> { result = "tool too weak (${tool ?: "no tool"})"; "too_weak" }
                is OreNodeService.Outcome.LevelLocked -> { result = "needs norma lv${out.required}"; "level_locked" }
                is OreNodeService.Outcome.PhaseLocked -> { result = "needs phase ${out.required}"; "phase_locked" }
                OreNodeService.Outcome.Regrowing -> { result = "regrowing"; learnPlaceholder(poi); "regrowing" }
                OreNodeService.Outcome.NotANode ->
                    if (sim.world.blockAt(me.world, cell) != null && sim.breakBlock(me, cell)) { result = "broke block"; "mined" }
                    else { result = "nothing to dig there"; "not_diggable" }
            }
        }
        val gained = oreTotal() - before
        if (gained > 0) oreMined += gained
        return StepOutcome(why == "mined", why, "$result${if (gained > 0) ", +$gained ore" else ""}")
    }

    private fun attack(arg: String, until: UntilDsl?, max: Int, interrupts: Boolean): StepOutcome {
        val target = (if (arg.equals("nearest", ignoreCase = true)) {
            others().filter(::hostile).minByOrNull { hypot(it.position.x - me.position.x, it.position.z - me.position.z) }
        } else {
            others().firstOrNull { it.name.equals(arg, ignoreCase = true) }
        }) ?: return StepOutcome(false, "unknown_target", "no living '$arg' (see enemies; `attack nearest` picks the closest live enemy)")
        ensureWeapon()
        var nextSwing = 0L
        val why = run(max, until, interrupts) {
            if (!sim.isAlive(target.id)) return@run "target_down"
            val d = hypot(target.position.x - me.position.x, target.position.z - me.position.z)
            if (d > 2.6) {
                navigator.follow(navHost, target.position.x, target.position.z, null, 2.0, SPEED, host.tick * 50L, null, 0f)
            } else {
                me.lookAt(target.position + Vec3(0.0, target.hitboxHeight / 2, 0.0))
                if (host.tick >= nextSwing) { sim.leftClick(me); nextSwing = host.tick + 10 }
            }
            null
        }
        return StepOutcome(why == "target_down", why, if (why == "target_down") "${target.name} is down" else "${target.name} ${hpBand(target)}")
    }

    private fun ensureWeapon() {
        val held = me.heldItem?.value
        if (held != null && WEAPON.any { held.contains(it) }) return
        inventory().keys.firstOrNull { k -> WEAPON.any { k.contains(it) } }?.let { sim.hold(me, it) }
    }

    private fun cast(arg: String): StepOutcome {
        when (arg) {
            "s1", "1" -> sim.rightClick(me)
            "s2", "2" -> sim.rightClick(me, sneaking = true)
            "s3", "3" -> sim.drop(me)
            "s4", "4" -> sim.drop(me, sneaking = true)
            else -> sim.cast(me, arg)
        }
        host.step(2)
        pump()
        return StepOutcome(true, "done", "cast $arg")
    }

    private fun hold(arg: String): StepOutcome {
        val items = inventory().keys.filter { !it.startsWith("ore-") }
        val item = arg.toIntOrNull()?.let { items.getOrNull(it - 1) } ?: arg.takeIf { it in items }
            ?: return StepOutcome(false, "unknown_item", "not carrying '$arg' (carrying: ${items.joinToString()})")
        sim.hold(me, item)
        return StepOutcome(true, "done", "holding $item")
    }

    /** Every offer the seat could consider, priced from the real catalogue against the real inventory and norma level. */
    private fun shopLines(): List<ShopLine> {
        val have = inventory()
        val level = normaLevel()
        return catalog.offers.map { o ->
            val base = o.upgradeFrom?.value
            val rarity = itemRegistry?.find(o.itemId)?.rarity
            val required = rarity?.let { levelGate?.requiredLevel(it) }?.takeIf { it > level }
            ShopLine(
                id = o.itemId.value, tab = o.tab.key,
                price = o.price.entries.associate { it.key.value.removePrefix("ore-") to it.value },
                missing = o.price.entries.mapNotNull { (ore, n) -> ((n - (have[ore.value] ?: 0)).takeIf { it > 0 })?.let { ore.value.removePrefix("ore-") to it } }.toMap(),
                lockedUntilLevel = required,
                needsBase = base?.takeIf { (have[it] ?: 0) < 1 },
            )
        }
    }

    /** Buyable lines, closest to affordable first; norma-locked and base-less upgrades sink to the end. */
    private fun shopRanking(): List<ShopLine> =
        shopLines().sortedWith(compareBy({ it.lockedUntilLevel != null }, { it.needsBase != null }, { it.missing.values.sum() }, { it.price.values.sum() }))

    private fun buy(arg: String, until: UntilDsl?, max: Int, interrupts: Boolean): StepOutcome {
        if (arg.isEmpty()) {
            val lines = shopRanking().take(SHOP_LIST_MAX)
            return StepOutcome(true, "listing", "${lines.count { it.affordable }} of ${catalog.offers.size} offers affordable now (closest first; `buy <id>`)", lines)
        }
        val offer = catalog.offers.firstOrNull { it.itemId.value == arg }
            ?: return StepOutcome(false, "unknown_item", "the shop does not sell '$arg' (bare `buy` lists what you can get)")
        val shop = pois.find("shop:$team")
        if (shop != null && hypot(shop.x - me.position.x, shop.z - me.position.z) > 5.0) {
            val walk = goto("shop:$team", until, max, interrupts, arrive = 3.0, target = shop)
            if (!walk.ok) return walk
        }
        val index = catalog.tab(offer.tab).indexOf(offer)
        val view = checkNotNull(sim.server.view(me.id))
        val router = sim.services.getOrThrow<IGuiIntentRouter>()
        val before = inventory()
        val msgBefore = view.messages.size
        router.dispatch(view, GuiIntent("matchshop.open", mapOf("team" to team)))
        router.dispatch(view, GuiIntent("matchshop.buy", mapOf("tab" to offer.tab.key, "index" to index.toString())))
        host.step(1)
        pump()
        val after = inventory()
        val got = (after[offer.itemId.value] ?: 0) > (before[offer.itemId.value] ?: 0) || offer.upgradeFrom != null && after != before
        if (got) purchases++
        val price = offer.price.entries.joinToString("+") { "${it.value} ${it.key.value.removePrefix("ore-")}" }
        return if (got) StepOutcome(true, "done", "bought ${offer.itemId.value} for $price")
        else StepOutcome(false, "refused", "could not buy ${offer.itemId.value} (needs $price; ${view.messages.drop(msgBefore).lastOrNull() ?: "no reason given"})")
    }

    private fun norma(arg: String, until: UntilDsl?, max: Int, interrupts: Boolean): StepOutcome {
        val progress = normaStore.get(host.matchId, me.id)
        if (arg == "star" || arg == "kill") {
            if (progress.goal.name.equals(arg, ignoreCase = true).not()) sim.command(me.name, "norma choose $arg")
        } else if (arg.isNotEmpty()) {
            return StepOutcome(false, "bad_arg", "norma star|kill")
        }
        val base = pois.find("base:$team") ?: return StepOutcome(false, "no_base", "no base for team $team")
        val before = normaLevel()
        if (hypot(base.x - me.position.x, base.z - me.position.z) > 8.0) {
            val walk = goto("base:$team", until, max, interrupts, arrive = 4.0, target = base)
            if (!walk.ok) return walk
        }
        val run = sim.command(me.name, "norma check")
        host.step(2)
        pump()
        val level = normaLevel()
        val stars = starCount()
        return if (level > before) StepOutcome(true, "done", "norma level $level")
        else StepOutcome(false, "not_ready", "norma check ${if (run.succeeded) "ran" else "refused"}; still lv$level (goal ${normaStore.get(host.matchId, me.id).goal.name.lowercase()}, stars $stars)")
    }

    // --- observation ----------------------------------------------------------------------------------------------
    /** Draws and clears the event digest accumulated since the previous decision (repeats collapsed). */
    fun drainEvents(): List<String> {
        pump()
        val out = ArrayList<String>()
        var i = 0
        while (i < pending.size) {
            var j = i
            while (j + 1 < pending.size && pending[j + 1] == pending[i]) j++
            out += if (j > i) "${pending[i]} x${j - i + 1}" else pending[i]
            i = j + 1
        }
        pending.clear()
        return out.takeLast(10)
    }

    fun isOver(): Boolean = ended || host.ended
    fun tick(): Long = host.tick
    fun phaseId(): String = phase

    data class View(
        val hp: Int, val pos: IntArray, val held: String?, val stars: Int, val ore: Map<String, Int>, val inv: Map<String, Int>,
        val normaLevel: Int, val normaGoal: String, val normaHave: Int, val normaNeed: Int, val wallet: Map<String, Long>, val tool: String?,
        val team: List<Triple<String, IntArray, String>>, val enemies: List<Triple<String, IntArray, String>>,
        val ready: Boolean, val cooldowns: Map<String, Int>, val dead: Boolean, val respawnIn: Int,
    )

    /** Item id -> remaining cooldown ticks, from the hotbar cooldown sweeps the items side asked for (the latest sweep per item). */
    private fun cooldowns(): Map<String, Int> =
        sim.sweeps.of(me.id).groupBy { it.item.value }.mapValues { (_, l) -> l.last() }
            .mapValues { (_, w) -> (w.tick + w.ticks - sim.tick).toInt() }.filterValues { it > 0 }

    fun view(): View {
        val inv = inventory()
        val progress = normaStore.get(host.matchId, me.id)
        val hud = normaService?.hudOf(me.id)
        fun entry(e: SimEntity) = Triple(e.name, intArrayOf(floor(e.position.x).toInt(), floor(e.position.y).toInt(), floor(e.position.z).toInt()), hpBand(e))
        val ally = others().filter { !hostile(it) && it.kind == SimEntityKind.PLAYER }
        val foes = others().filter(::hostile).sortedBy { hypot(it.position.x - me.position.x, it.position.z - me.position.z) }.take(6)
        return View(
            hp = hp().toInt(),
            pos = intArrayOf(floor(me.position.x).toInt(), floor(me.position.y).toInt(), floor(me.position.z).toInt()),
            held = me.heldItem?.value,
            stars = starCount(),
            ore = inv.filterKeys { it.startsWith("ore-") }.mapKeys { it.key.removePrefix("ore-") },
            inv = inv.filterKeys { !it.startsWith("ore-") },
            normaLevel = progress.level,
            normaGoal = progress.goal.name.lowercase(),
            normaHave = hud?.progress ?: 0,
            normaNeed = hud?.target ?: 0,
            wallet = wallet(),
            tool = inventoryTool("pickaxe"),
            team = ally.map(::entry),
            enemies = foes.map(::entry),
            ready = progress.readyNotified,
            cooldowns = cooldowns(),
            dead = !sim.isAlive(me.id),
            respawnIn = deadSince?.let { (it + RESPAWN_TICKS - host.tick).toInt().coerceAtLeast(0) } ?: 0,
        )
    }

    fun map(scale: Int): MapRender {
        val p = plan
        val marks = ArrayList<MapMark>()
        for (poi in pois.pois) when (poi.kind) {
            "shop" -> marks += MapMark(poi.x, poi.z, '$', 4)
            "mid-ore", "gen" -> marks += MapMark(poi.x, poi.z, 'o', 3)
            "pillar" -> {
                val up = sim.world.blockAt(me.world, Vec3(floor(poi.x), floor(poi.y), floor(poi.z))) != null
                marks += MapMark(poi.x, poi.z, if (up) '#' else 'x', 2)
            }
        }
        for (s in sim.starDrops.lying()) marks += MapMark(s.position.x, s.position.z, '*', 5)
        for (e in others()) {
            val c = e.name.firstOrNull() ?: '?'
            marks += if (hostile(e)) MapMark(e.position.x, e.position.z, c.uppercaseChar(), 7) else MapMark(e.position.x, e.position.z, c.lowercaseChar(), 6)
        }
        marks += MapMark(me.position.x, me.position.z, '@', 9)
        return AsciiMap(p.minX, p.maxX, p.minZ, p.maxZ) { x, z -> p.hasTop(x, z) || (p.minY..p.maxY).any { y -> p.isSolid(x, y, z) } }.render(scale, marks)
    }

    /** `look`: the blocks and things in a 7x7 square around the seat, with y. */
    fun look(): List<String> {
        val cx = floor(me.position.x).toInt()
        val cz = floor(me.position.z).toInt()
        val fy = floor(me.position.y).toInt()
        val rows = ArrayList<String>()
        for (dz in -3..3) {
            val sb = StringBuilder()
            for (dx in -3..3) {
                val x = cx + dx
                val z = cz + dz
                val top = (fy + 4 downTo fy - 8).firstOrNull { terrain.isSolid(x, it, z) == true || sim.world.blockAt(me.world, Vec3(x.toDouble(), it.toDouble(), z.toDouble())) != null }
                sb.append(if (dx == 0 && dz == 0) "@" else top?.let { (it - fy).let { d -> if (d >= 0) "+$d" else "$d" } } ?: "__").append(' ')
            }
            rows += "z=${cz + dz} ${sb.toString().trimEnd()}"
        }
        rows += "x=${cx - 3}..${cx + 3}, y=$fy; each cell = top solid relative to your feet (+up), __ = void"
        return rows
    }

    fun menu(): List<String> {
        val m = ArrayList<String>()
        val v = view()
        val px = me.position.x
        val pz = me.position.z
        fun d(p: Poi) = "%.0f".format(p.distanceTo(px, pz))
        pois.find("shop:$team")?.let { m += "goto shop:$team (${d(it)})" }
        pois.find("base:$team")?.let { m += "goto base:$team (${d(it)}) / norma ${v.normaGoal}" }
        sim.starDrops.lying().minByOrNull { hypot(it.position.x - px, it.position.z - pz) }?.let {
            m += "goto star (${"%.0f".format(hypot(it.position.x - px, it.position.z - pz))})"
        }
        pois.ofKind("mid-ore").groupBy { it.ore }.mapNotNull { (_, l) -> l.minByOrNull { it.distanceTo(px, pz) } }.sortedBy { it.distanceTo(px, pz) }.take(3)
            .forEach { m += "dig ${it.name} (${d(it)})" }
        pois.ofKind("pillar").filter { !it.name.contains(":$team:") }.minByOrNull { it.distanceTo(px, pz) }?.let { m += "dig ${it.name} (${d(it)})" }
        others().filter(::hostile).minByOrNull { hypot(it.position.x - px, it.position.z - pz) }?.let {
            m += "attack ${it.name} (${"%.0f".format(hypot(it.position.x - px, it.position.z - pz))})"
        }
        if (v.dead) {
            emptyMenus++
            return listOf("wait <ticks> (dead, respawn in ~${v.respawnIn} ticks; skip / act wait for it by themselves)")
        }
        val atShop = pois.find("shop:$team")?.let { hypot(it.x - px, it.z - pz) <= SHOP_NEAR } ?: false
        val buyable = shopRanking().filter { it.lockedUntilLevel == null && it.needsBase == null }
        (if (atShop) buyable.take(MENU_BUY_AT_SHOP) else buyable.filter { it.affordable }.take(4)).forEach { m += buyLine(it) }
        m += "cast s1|s2|s3|s4"
        m += "wait <ticks> / hold <item>"
        if (m.size <= 3) emptyMenus++
        return m
    }

    /** `buy dagger (6 iron, short 5 iron)` - the same `verb target (info)` style as the goto lines. */
    private fun buyLine(l: ShopLine) = "buy ${l.id} (${l.priceText}, ${if (l.affordable) "affordable" else "short " + l.missing.entries.joinToString("+") { "${it.value} ${it.key} ore" }})"

    /** Balances per currency from the real economy ledger (lower-case currency name -> amount). */
    fun wallet(): Map<String, Long> = Currency.entries.associate { it.name.lowercase() to ledger.balance(me.id, it) }

    fun summaryStats(): Map<String, Any> = mapOf(
        "ticks" to host.tick, "decisions" to decisions, "skipped_ticks" to skippedTicks, "longest_wait" to longestWait,
        "longest_wait_at" to longestWaitAt, "empty_menus" to emptyMenus, "stars_gained" to starsGained, "ore_mined" to oreMined,
        "decisions_ok" to decisions - decisionsFailed, "decisions_failed" to decisionsFailed,
        "purchases" to purchases, "kills" to kills, "deaths" to deaths, "norma_level" to normaLevel(), "phase" to phase,
    )

    fun requestInfo() = "stage=${host.stageId} team=$team origin=${origin.x.toInt()},${origin.z.toInt()}"

    companion object {
        const val SPEED = 0.28
        /** The match's default respawn delay (DeathRespawnConfig), for the "respawn in" read-out only. */
        const val RESPAWN_TICKS = com.astarworks.astera.match.domain.death.DeathRespawnConfig.DEFAULT_RESPAWN_DELAY_SECONDS * 20
        const val REACH = 4.5
        private const val REGROW_POLL = 20
        private const val HIT_COOLDOWN = 6L
        private const val SHOP_LIST_MAX = 12
        private const val MENU_BUY_AT_SHOP = 6
        private const val SHOP_NEAR = 5.0
        /** Stops that end a step without the decision itself being wrong. */
        private val SOFT_STOPS = setOf("timeout", "hit_taken", "enemy_within_12", "phase_change", "norma", "died", "match_ended", "done", "arrived", "mined", "target_down", "listing", "look")
        private val WEAPON = listOf("sword", "dagger", "spear", "blade", "katana", "axe", "scythe", "hammer", "bow", "wand", "staff")
    }
}
