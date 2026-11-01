package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import com.astarworks.astera.match.domain.event.StarsCollected

/**
 * First-star probe: `./gradlew :tools:game-sim:firstStar -Pseeds=1,2,3 -Psize=3` prints, per team and seed, the median / max
 * seconds from match start to each player's first star (any source) and the first star that pops within 20 blocks of the
 * team's spawn centre. Additive diagnostic; reads only sim events and the sim's star drop records.
 */
fun main(args: Array<String>) {
    val seeds = args.getOrElse(0) { "1" }.split(',').mapNotNull { it.trim().toLongOrNull() }
    val size = args.getOrNull(1)?.toIntOrNull() ?: 3
    val horizon = (args.getOrNull(2)?.toIntOrNull() ?: 120) * 20
    println("| seed | team | n | first-star median s | max s | never | near-spawn pop s | per-player s |")
    println("|---|---|---:|---:|---:|---:|---:|---|")
    for (seed in seeds) {
        MatchDriver.boot(MatchConfig(seed = seed, botsPerTeam = size)).use { d ->
            val spawn = HashMap<String, Vec3>()
            val teamOf = HashMap<PlayerId, String>()
            for (s in d.seats) { teamOf[s.entity.id] = s.team }
            val byTeam = d.seats.groupBy { it.team }
            var started: Long? = null
            val first = HashMap<PlayerId, Long>()
            val seen = HashSet<String>()
            val nearPop = HashMap<String, Long>()
            fun centre(team: String): Vec3 = spawn.getOrPut(team) {
                val ps = byTeam.getValue(team).map { it.entity.position }
                Vec3(ps.map { it.x }.average(), ps.map { it.y }.average(), ps.map { it.z }.average())
            }
            var cursor = 0
            d.onTick { tick ->
                if (started == null) {
                    started = d.sim.events.filterIsInstance<MatchStarted>().firstOrNull()?.atTick
                    if (started != null) byTeam.keys.forEach { centre(it) }
                }
                val t0 = started ?: return@onTick
                for (e in d.sim.events.subList(cursor, d.sim.events.size).toList()) if (e is StarsCollected) first.putIfAbsent(e.playerId, tick - t0)
                cursor = d.sim.events.size
                for (drop in d.sim.starDrops.lying()) if (seen.add(drop.handle)) {
                    for ((team, c) in spawn) {
                        val dx = drop.position.x - c.x; val dz = drop.position.z - c.z
                        if (dx * dx + dz * dz <= 400.0) nearPop.putIfAbsent(team, tick - t0)
                    }
                }
            }
            d.step(horizon + 2400)
            for ((team, seats) in byTeam) {
                val t = seats.mapNotNull { first[it.entity.id] }.sorted().map { it / 20.0 }
                val never = seats.size - t.size
                val med = if (t.isEmpty()) "-" else "%.1f".format(t[t.size / 2])
                val max = if (t.isEmpty()) "-" else "%.1f".format(t.last())
                println("| $seed | $team | ${seats.size} | $med | $max | $never | ${nearPop[team]?.let { "%.1f".format(it / 20.0) } ?: "-"} | ${seats.joinToString(", ") { st -> first[st.entity.id]?.let { "%.1f".format(it / 20.0) } ?: "-" }} |")
            }
        }
    }
}
