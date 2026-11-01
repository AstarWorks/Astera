package com.astarworks.astera.gamesim.playable.metrics

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor

/**
 * Runs N headless matches (one per seed), writes `<outDir>/<run>/metrics.json` per match and `<outDir>/summary-<firstSeed>-<lastSeed>.md` (one file per batch, earlier batches survive)
 * (min / median / max and a short distribution per metric). Same content, only the seed differs (design 4.1).
 */
object MassPlay {
    private val pretty = Json { prettyPrint = true }

    /** Per-batch summary file named after the seed range, so a later batch never overwrites an earlier one. */
    fun summaryFileName(seeds: List<Long>) = "summary-${seeds.first()}-${seeds.last()}.md"

    class Config(
        /** Hard cap of one match, ticks (20/s). A match still running then is reported as `running`. */
        val maxTicks: Int = System.getProperty("astera.massplay.maxTicks")?.toIntOrNull() ?: 24_000,
        /** Render a PNG every this many ticks (0 = only the final frame). */
        val frameEveryTicks: Int = 1_200,
        val renderFrames: Boolean = true,
    )

    class RunResult(val run: String, val seed: Long, val result: MatchMetricsResult, val endReason: String, val seatOrder: List<String> = emptyList())

    fun run(
        seeds: List<Long>,
        outDir: Path,
        config: Config = Config(),
        newMatch: (seed: Long) -> MatchRun,
    ): List<RunResult> {
        val dup = seeds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(dup.isEmpty()) { "massPlay: runs in one batch would replay identical matches, duplicate seeds ${dup.joinToString()} in ${seeds.joinToString()}" }
        Files.createDirectories(outDir)
        val results = seeds.map { seed -> playOne(seed, outDir, config, newMatch) }
        Files.writeString(outDir.resolve(summaryFileName(seeds)), summary(results))
        return results
    }

    private fun playOne(seed: Long, outDir: Path, config: Config, newMatch: (Long) -> MatchRun): RunResult {
        val run = "seed-$seed"
        val dir = outDir.resolve(run)
        Files.createDirectories(dir)
        val match = newMatch(seed)
        try {
            val metrics = MatchMetrics(match.sim, roleOf = { p -> match.brainOf(p)?.currentRole?.name }, decisionKindOf = { p -> match.brainOf(p)?.currentDecisionKind })
            var frames: FrameRenderer? = null
            fun frame(tick: Long) {
                if (!config.renderFrames) return
                val r = frames ?: run {
                    val m = metrics.currentMatchId ?: return
                    val s = metrics.currentStageId ?: return
                    FrameRenderer(match.sim, s, m).also { frames = it }
                }
                r.render(tick, dir)
            }
            match.onTick { tick ->
                metrics.onTick(tick)
                if (config.frameEveryTicks > 0 && tick % config.frameEveryTicks == 0L) frame(tick)
            }
            var ticks = 0
            while (!match.ended && ticks < config.maxTicks) { match.step(20); ticks += 20 }
            frame(match.sim.tick)
            val result = metrics.finish()
            val seatOrder = match.seatOrder
            val withSeed = kotlinx.serialization.json.JsonObject(
                mapOf("seed" to kotlinx.serialization.json.JsonPrimitive(seed), "seat_order" to kotlinx.serialization.json.JsonArray(seatOrder.map { kotlinx.serialization.json.JsonPrimitive(it) })) + result.json,
            )
            Files.writeString(dir.resolve("metrics.json"), pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), withSeed))
            val reason = (result.json["endReason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
            return RunResult(run, seed, result, reason, seatOrder)
        } finally {
            (match as? AutoCloseable)?.close() ?: match.sim.shutdown()
        }
    }

    private const val BOOTSTRAP_RESAMPLES = 1000
    private const val BOOTSTRAP_SEED = 20261102L
    private val PHASES = listOf("p1" to "P1 build", "p2" to "P2 engage", "p3" to "P3 center", "p4plus" to "P4+ escalation / sudden death")

    private fun RunResult.sc(key: String) = result.scalars[key] ?: 0.0

    /** Pooled kill rate: sum(kills) / sum(match minutes). Null when no minutes were played. */
    fun pooledRate(kills: Double, minutes: Double): Double? = if (minutes > 0.0) kills / minutes else null

    /**
     * Deterministic bootstrap 90% interval (5th and 95th percentile, nearest rank of the sorted resamples) of the pooled
     * rate sum(kills) / sum(minutes): [BOOTSTRAP_RESAMPLES] resamples of the matches with replacement, `java.util.Random([BOOTSTRAP_SEED])`.
     * [kills] and [minutes] are per-match, same order.
     */
    fun bootstrapInterval(kills: List<Double>, minutes: List<Double>): Pair<Double, Double>? {
        require(kills.size == minutes.size)
        if (kills.isEmpty()) return null
        val rng = java.util.Random(BOOTSTRAP_SEED)
        val n = kills.size
        val rates = ArrayList<Double>(BOOTSTRAP_RESAMPLES)
        repeat(BOOTSTRAP_RESAMPLES) {
            var k = 0.0; var m = 0.0
            repeat(n) { val i = rng.nextInt(n); k += kills[i]; m += minutes[i] }
            pooledRate(k, m)?.let { rates += it }
        }
        if (rates.isEmpty()) return null
        rates.sort()
        return rates[(rates.size * 0.05).toInt()] to rates[(rates.size * 0.95).toInt().coerceAtMost(rates.size - 1)]
    }

    /** End kind of a run from the normalised `end_reason`: elimination, hp_decision (the P5 HP tiebreak = a stall), draw, running (= hit the tick cap = timeout), other. */
    private fun endKind(r: RunResult) = (r.result.json["end_reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "other"

    /** First line of the summary: matches with at least one P3+ stall window, total windows, stall share. See [killRateSection] for definitions. */
    fun stallLine(results: List<RunResult>): String {
        val n = results.size
        val windows = results.map { it.sc("stall_windows_p3plus") }
        val kinds = results.map { endKind(it) }
        val hp = kinds.count { it == "hp_decision" }
        val timeout = kinds.count { it == "running" }
        return "Stall signature: matches with at least one stall window ${windows.count { it >= 1.0 }} of $n; total stall windows ${fmt(windows.sum())} " +
            "(stall window = a maximal gap of at least 3 min inside P3+ with no cross-team engagement; engagement = cross-team player damage, or an alive cross-team pair within 12 blocks, sampled each second). " +
            "Stall share = (hp_decision + timeout) / matches = ($hp + $timeout) / $n = ${pct((hp + timeout).toDouble(), n.toDouble())}."
    }

    /**
     * Headline kill rate. Definitions:
     * - pooled kills/min = sum(kills_total) / sum(duration minutes) over all matches (a long stall weighs by its minutes, not as one match).
     * - per phase: sum(kills in phase) / sum(minutes in phase); phases are [start, next start), P4+ = Phase4Escalation + Phase5SuddenDeath, a phase never reached adds 0 kills and 0 minutes.
     * - P2+ = P2 + P3 + P4+ pooled together.
     * - 90% interval: bootstrap over matches, see [bootstrapInterval].
     * - median / mean: of the per-match kills_per_min column (unpooled, kept for comparison with older reports).
     * - end counts: elimination (TeamVictory), hp_decision (PillarHpDecision, a stall), draw, timeout (still running at the tick cap).
     */
    fun killRateSection(results: List<RunResult>): String = buildString {
        val kills = results.map { it.sc("kills_total") }
        val minutes = results.map { it.sc("duration_s") / 60.0 }
        val pooled = pooledRate(kills.sum(), minutes.sum())
        val ci = bootstrapInterval(kills, minutes)
        val perMatch = results.mapNotNull { it.result.scalars["kills_per_min"] }.sorted()
        appendLine("## Kill rate (pooled over ${results.size} matches)")
        appendLine()
        appendLine("**Headline: pooled kills/min = ${pooled?.let { "%.2f".format(it) } ?: "n/a"}** " +
            "(${fmt(kills.sum())} kills / ${"%.1f".format(minutes.sum())} match-min; bootstrap 90% interval ${ci?.let { "[%.2f, %.2f]".format(it.first, it.second) } ?: "n/a"}, $BOOTSTRAP_RESAMPLES resamples, fixed seed). " +
            "Per-match median ${if (perMatch.isEmpty()) "n/a" else "%.2f".format(median(perMatch))}, mean ${if (perMatch.isEmpty()) "n/a" else "%.2f".format(perMatch.average())} (extra columns, not the gate).")
        appendLine()
        appendLine("| phase | kills | minutes | pooled kills/min |")
        appendLine("|---|---:|---:|---:|")
        fun row(label: String, keys: List<String>) {
            val k = keys.sumOf { key -> results.sumOf { it.sc("kills_$key") } }
            val m = keys.sumOf { key -> results.sumOf { it.sc("minutes_$key") } }
            appendLine("| $label | ${fmt(k)} | ${"%.1f".format(m)} | ${pooledRate(k, m)?.let { "%.2f".format(it) } ?: "n/a"} |")
        }
        for ((key, label) in PHASES) row(label, listOf(key))
        row("P2+ (P2 + P3 + P4+)", listOf("p2", "p3", "p4plus"))
        appendLine()
        val kinds = results.map { endKind(it) }.groupingBy { it }.eachCount()
        appendLine("Match ends: elimination ${kinds["elimination"] ?: 0}, hp_decision (stall) ${kinds["hp_decision"] ?: 0}, draw ${kinds["draw"] ?: 0}, timeout (running at cap) ${kinds["running"] ?: 0}, other ${kinds.filterKeys { it !in setOf("elimination", "hp_decision", "draw", "running") }.values.sum()}.")
        appendLine()
    }

    private const val SPARK = "▁▂▃▄▅▆▇█"

    private fun spark(values: List<Double>): String {
        val lo = values.min(); val hi = values.max()
        if (hi - lo < 1e-9) return SPARK[3].toString().repeat(values.size.coerceAtMost(12))
        val bins = IntArray(8)
        for (v in values) bins[floor((v - lo) / (hi - lo) * 7.999).toInt().coerceIn(0, 7)]++
        val top = bins.max()
        return bins.joinToString("") { SPARK[(it * 7 / top).coerceIn(0, 7)].toString() }
    }

    private fun fmt(v: Double) = if (v == floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else "%.2f".format(v)

    fun summary(results: List<RunResult>): String = buildString {
        appendLine("# Mass play summary")
        appendLine()
        appendLine(stallLine(results))
        appendLine()
        appendLine("Matches: ${results.size} (seeds ${results.joinToString { it.seed.toString() }}). End reasons: " +
            results.groupingBy { it.endReason }.eachCount().entries.joinToString { "${it.key} x${it.value}" })
        appendLine()
        appendLine(killRateSection(results))
        appendLine("| metric | n | min | median | max | distribution (low to high) |")
        appendLine("|---|---:|---:|---:|---:|---|")
        val keys = results.flatMap { it.result.scalars.keys }.distinct()
        for (key in keys) {
            val values = results.mapNotNull { it.result.scalars[key] }.sorted()
            if (values.isEmpty()) {
                val reason = results.firstNotNullOfOrNull { it.result.notAvailable[key] } ?: "no value"
                appendLine("| $key | 0 | n/a | n/a | n/a | n/a: $reason |")
                continue
            }
            val median = if (values.size % 2 == 1) values[values.size / 2] else (values[values.size / 2 - 1] + values[values.size / 2]) / 2
            appendLine("| $key | ${values.size} | ${fmt(values.first())} | ${fmt(median)} | ${fmt(values.last())} | ${spark(values)} |")
        }
        appendLine()
        appendLine("Pillar damage per run and team (dealt = to enemy pillars by the attacker's team; taken includes unattributed; hp_left = standing pillar HP at the end):")
        appendLine()
        appendLine("| run | seed | end_reason | end_decided_by | team | pillar_damage_dealt | pillar_damage_taken | pillar_hp_left | unattributed (match) | taken_by_players (team) | bleed (team) | unknown (team) | first_tier_loss_min | end_play_hp | end_bleed_taken |")
        appendLine("|---|---:|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for (r in results) {
            val teams = r.result.json["teams"] as? kotlinx.serialization.json.JsonObject ?: continue
            val endKind = (r.result.json["end_reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "n/a"
            val decidedBy = (r.result.json["end_decided_by"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content ?: "-"
            val unattr = r.result.scalars["pillar_damage_unattributed"] ?: 0.0
            fun endMap(k: String, team: String) = ((r.result.json[k] as? kotlinx.serialization.json.JsonObject)?.get(team) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "n/a"
            for ((team, v) in teams) {
                val o = v as kotlinx.serialization.json.JsonObject
                fun num(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                val left = ((o["pillar_hp_left"] as? kotlinx.serialization.json.JsonObject)?.get("sum") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                appendLine("| ${r.run} | ${r.seed} | $endKind | $decidedBy | $team | ${fmt(num("pillar_damage_dealt"))} | ${fmt(num("pillar_damage_taken"))} | ${fmt(left)} | ${fmt(unattr)} | ${fmt(num("pillar_damage_taken_by_players"))} | ${fmt(num("pillar_damage_bleed"))} | ${fmt(num("pillar_damage_unknown"))} | ${firstTierLossMin(o)?.let { "%.2f".format(it) } ?: "none"} | ${endMap("end_play_hp", team)} | ${endMap("end_bleed_taken", team)} |")
            }
        }
        appendLine()
        appendLine(aggregate(results))
        appendLine("Seat / spawn order per run (the order MatchDriver seats players and enters them into their team, `name@team`):")
        appendLine()
        appendLine("| run | seed | seat_order |")
        appendLine("|---|---:|---|")
        for (r in results) appendLine("| ${r.run} | ${r.seed} | ${r.seatOrder.joinToString(", ").ifEmpty { "n/a" }} |")
        appendLine()
        appendLine("First match-shop purchase per run (seconds from MatchStarted; budget: median at most 150 s):")
        appendLine()
        appendLine("| run | seed | first_buy_median_s | never_bought | players |")
        appendLine("|---|---:|---:|---:|---:|")
        for (r in results) {
            val med = r.result.scalars["first_buy_median_s"]
            appendLine("| ${r.run} | ${r.seed} | ${med?.let { fmt(it) } ?: "n/a"} | ${fmt(r.result.scalars["never_bought"] ?: 0.0)} | ${fmt(r.result.scalars["players"] ?: 0.0)} |")
        }
        val pooled = results.flatMap { it.result.firstBuySeconds }.sorted()
        val pooledMedian = when {
            pooled.isEmpty() -> null
            pooled.size % 2 == 1 -> pooled[pooled.size / 2]
            else -> (pooled[pooled.size / 2 - 1] + pooled[pooled.size / 2]) / 2
        }
        appendLine("| all runs (pooled buyers) | - | ${pooledMedian?.let { fmt(it) } ?: "n/a"} | ${fmt(results.sumOf { it.result.scalars["never_bought"] ?: 0.0 })} | ${fmt(results.sumOf { it.result.scalars["players"] ?: 0.0 })} |")
        val notes = results.flatMap { it.result.notAvailable.entries }.groupBy({ it.key }, { it.value }).filterKeys { it !in keys }
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("Notes / n/a columns:")
            for ((k, v) in notes) appendLine("- $k: ${v.first()}")
        }
    }

    private fun firstTierLossMin(team: kotlinx.serialization.json.JsonObject): Double? =
        (team["first_tier_loss_min"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.toDoubleOrNull()

    private fun median(sorted: List<Double>): Double =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

    private fun pct(part: Double, whole: Double) = if (whole > 0.0) "%.1f%%".format(100.0 * part / whole) else "n/a"

    /**
     * Batch aggregate: per team win share (draws separate), pillar damage taken split into dealt-by-players (taken minus
     * the unattributed part) and unattributed (bleed + unknown), the minute of the team's first tier loss; and the batch
     * player share of pillar damage, sum(dealt) / sum(taken).
     */
    fun aggregate(results: List<RunResult>): String = buildString {
        fun teamsOf(r: RunResult) = r.result.json["teams"] as? kotlinx.serialization.json.JsonObject
        fun kotlinx.serialization.json.JsonObject.num(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        val teams = results.flatMap { teamsOf(it)?.keys ?: emptySet() }.distinct().sorted()
        val draws = results.count { (it.result.json["end_reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "draw" }
        val n = results.size
        appendLine("## Batch aggregate (${n} runs)")
        appendLine()
        appendLine("Draws: $draws of $n (counted separately from wins). Taken = dealt by players + unattributed (bleed + unknown); taken_by_players = taken minus bleed (so it includes self-inflicted and any unknown).")
        appendLine()
        appendLine("| team | wins | runs | win share | pillar taken | taken: by players | taken: unattributed | of which bleed | of which unknown | first tier loss min (median / mean) | runs without a tier loss |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|")
        var sumTaken = 0.0; var sumDealt = 0.0
        for (t in teams) {
            val wins = results.count {
                val w = (it.result.json["winner"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p !is kotlinx.serialization.json.JsonNull }?.content
                w == t && (it.result.json["end_reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "draw"
            }
            val objs = results.mapNotNull { teamsOf(it)?.get(t) as? kotlinx.serialization.json.JsonObject }
            val taken = objs.sumOf { it.num("pillar_damage_taken") }
            val unattr = objs.sumOf { it.num("pillar_damage_taken_unattributed") }
            val byPlayers = objs.sumOf { it.num("pillar_damage_taken_by_players") }
            val bleed = objs.sumOf { it.num("pillar_damage_bleed") }
            val unknown = objs.sumOf { it.num("pillar_damage_unknown") }
            sumDealt += objs.sumOf { it.num("pillar_damage_dealt") }
            sumTaken += taken
            val lossMins = objs.mapNotNull { firstTierLossMin(it) }.sorted()
            val lossCell = if (lossMins.isEmpty()) "n/a" else "%.2f / %.2f".format(median(lossMins), lossMins.average())
            appendLine("| $t | $wins | $n | ${pct(wins.toDouble(), n.toDouble())} | ${fmt(taken)} | ${fmt(byPlayers)} (${pct(byPlayers, taken)}) | ${fmt(unattr)} (${pct(unattr, taken)}) | ${fmt(bleed)} (${pct(bleed, taken)}) | ${fmt(unknown)} (${pct(unknown, taken)}) | $lossCell | ${n - lossMins.size} |")
        }
        appendLine()
        appendLine("Player share of pillar damage (sum dealt / sum taken): ${fmt(sumDealt)} / ${fmt(sumTaken)} = ${pct(sumDealt, sumTaken)}")
        appendLine()
        val bleedApps = results.sumOf { it.result.scalars["bleed_applications"] ?: 0.0 }
        appendLine("Bleed applications (ticks on which the Phase 5 bleed hit a pillar): ${fmt(bleedApps)} over $n runs.")
        appendLine()
    }
}
