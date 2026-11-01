package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.star.StarSource
import kotlin.math.hypot

/**
 * Kill-value probe: `./gradlew -q :tools:game-sim:killValue -Pseeds=1,2,...,10 -Psizes=3,6,12` prints, per N (players per team, 3 teams,
 * annihilation-arena, full match), how much a kill is worth against farming. Additive diagnostic; reads only sim events and star drops.
 *
 * Attribution rule: a death drop is a star drop that appears within 4 blocks of a victim on the tick of a kill (or the next 2 ticks). It counts
 * for the KILLER when the killer is the one who picks it up, within 8 blocks (horizontal) of the kill spot and within 10 s of the kill. Stars per
 * kill = those picked-up death-drop stars + the bounty paid for the kill. Farm = every other star (mining, blocks, caches, spots, waves); death
 * drops picked up by anyone else or late/far are neither farm nor kill value. R = stars per kill / (farm stars per minute x 0.5).
 */
private const val RADIUS = 8.0
private const val WINDOW_TICKS = 200L
private const val DIRECT = "star-source-"

private class Stat(val id: PlayerId) {
    var kills = 0; var killDrop = 0; var bounty = 0; var farm = 0; var total = 0
}

fun main(args: Array<String>) {
    val seeds = args.getOrElse(0) { "1" }.split(',').mapNotNull { it.trim().toLongOrNull() }
    val sizes = args.getOrElse(1) { "3,6,12" }.split(',').mapNotNull { it.trim().toIntOrNull() }
    val maxTicks = args.getOrNull(2)?.toIntOrNull() ?: 24000
    println("| N/team | seeds | players | kills | share from kill drops (median [p25-p75]) | stars/kill (pooled; median [p25-p75]) | farm/min (median [p25-p75]) | R pooled | R median [p25-p75] |")
    println("|---|---:|---:|---:|---|---|---|---:|---|")
    for (n in sizes) {
        val stats = ArrayList<Pair<Stat, Double>>() // stat, match minutes
        for (seed in seeds) {
            MatchDriver.boot(MatchConfig(seed = seed, botsPerTeam = n)).use { d ->
                val by = d.seats.associate { it.entity.id to Stat(it.entity.id) }
                class Kill(val killer: PlayerId, val pos: Vec3, val tick: Long)
                val kills = ArrayList<Kill>()
                class DeathDrop(val kill: Kill, val amount: Int)
                val deathDrops = HashMap<String, DeathDrop>()
                var prev = HashMap<String, Int>()
                var started: Long? = null
                var cursor = 0
                var endTick = 0L
                d.onTick { tick ->
                    if (started == null) started = d.sim.events.filterIsInstance<MatchStarted>().firstOrNull()?.atTick
                    val fresh = d.sim.events.subList(cursor, d.sim.events.size).toList()
                    val before = cursor
                    cursor = d.sim.events.size
                    for (e in fresh) if (e is PlayerKilled && e.killer != null && e.killer != e.victim && e.killer in by) {
                        val p = d.sim.world.find(e.victim)?.position ?: continue
                        kills += Kill(e.killer!!, p, tick); by.getValue(e.killer!!).kills++
                    }
                    val now = HashMap<String, Int>()
                    for (drop in d.sim.starDrops.lying()) {
                        now[drop.handle] = drop.amount
                        if (drop.handle !in prev) kills.lastOrNull { tick - it.tick <= 2 && hypot(it.pos.x - drop.position.x, it.pos.z - drop.position.z) <= 4.0 }
                            ?.let { deathDrops[drop.handle] = DeathDrop(it, drop.amount) }
                    }
                    val taken = deathDrops.keys.filter { it !in now && it in prev }.toMutableList()
                    var lastSupplied: StarSupplied? = null
                    for ((i, e) in fresh.withIndex()) {
                        if (e is StarSupplied) { lastSupplied = e; continue }
                        if (e !is StarsCollected) { continue }
                        val st = by[e.playerId] ?: continue
                        st.total += e.amount
                        val direct = lastSupplied?.takeIf { it.amount == e.amount && it.generatorId.value.startsWith(DIRECT) }
                        lastSupplied = null
                        if (direct != null) {
                            if (direct.generatorId.value == DIRECT + StarSource.BOUNTY.key) st.bounty += e.amount else st.farm += e.amount
                            continue
                        }
                        val h = taken.firstOrNull { deathDrops.getValue(it).amount == e.amount }
                        if (h == null) { st.farm += e.amount; continue }
                        taken.remove(h)
                        val dd = deathDrops.getValue(h)
                        val at = d.sim.world.find(e.playerId)?.position
                        if (dd.kill.killer == e.playerId && at != null && tick - dd.kill.tick <= WINDOW_TICKS &&
                            hypot(at.x - dd.kill.pos.x, at.z - dd.kill.pos.z) <= RADIUS) st.killDrop += e.amount
                    }
                    prev = now
                    endTick = tick
                }
                d.stepUntil(maxTicks + 1200) { d.ended || (started?.let { d.tick - it > maxTicks } ?: false) }
                val minutes = (endTick - (started ?: 0L)) / 1200.0
                by.values.forEach { stats += it to minutes }
            }
        }
        fun q(v: List<Double>, p: Double): Double = if (v.isEmpty()) Double.NaN else v.sorted().let { it[((it.size - 1) * p).toInt().coerceIn(0, it.size - 1)] }
        fun spread(v: List<Double>, f: String = "%.2f") = "${f.format(q(v, 0.5))} [${f.format(q(v, 0.25))}-${f.format(q(v, 0.75))}]"
        val share = stats.filter { it.first.total > 0 }.map { it.first.killDrop.toDouble() / it.first.total }
        val farm = stats.map { (s, m) -> s.farm / m }
        val killers = stats.filter { it.first.kills > 0 }
        val perKill = killers.map { (s, _) -> (s.killDrop + s.bounty).toDouble() / s.kills }
        val rPer = killers.map { (s, m) -> ((s.killDrop + s.bounty).toDouble() / s.kills) / ((s.farm / m) * 0.5) }
        val totalKills = stats.sumOf { it.first.kills }
        val pooledPerKill = if (totalKills == 0) Double.NaN else stats.sumOf { it.first.killDrop + it.first.bounty }.toDouble() / totalKills
        val rPooled = pooledPerKill / (farm.average() * 0.5)
        println("| $n | ${seeds.size} | ${stats.size} | $totalKills | ${spread(share.map { it * 100 }, "%.1f")}% | %.2f; ${spread(perKill)} | ${spread(farm)} | %.2f | ${spread(rPer)} |".format(pooledPerKill, rPooled))
    }
}
