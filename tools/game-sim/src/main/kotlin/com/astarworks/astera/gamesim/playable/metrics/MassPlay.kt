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

    class RunResult(val run: String, val seed: Long, val result: MatchMetricsResult, val endReason: String)

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
            val withSeed = kotlinx.serialization.json.JsonObject(mapOf("seed" to kotlinx.serialization.json.JsonPrimitive(seed)) + result.json)
            Files.writeString(dir.resolve("metrics.json"), pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), withSeed))
            val reason = (result.json["endReason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
            return RunResult(run, seed, result, reason)
        } finally {
            (match as? AutoCloseable)?.close() ?: match.sim.shutdown()
        }
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
        appendLine("Matches: ${results.size} (seeds ${results.joinToString { it.seed.toString() }}). End reasons: " +
            results.groupingBy { it.endReason }.eachCount().entries.joinToString { "${it.key} x${it.value}" })
        appendLine()
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
        appendLine("| run | seed | end_reason | end_decided_by | team | pillar_damage_dealt | pillar_damage_taken | pillar_hp_left | unattributed (match) |")
        appendLine("|---|---:|---|---|---|---:|---:|---:|---:|")
        for (r in results) {
            val teams = r.result.json["teams"] as? kotlinx.serialization.json.JsonObject ?: continue
            val endKind = (r.result.json["end_reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "n/a"
            val decidedBy = (r.result.json["end_decided_by"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content ?: "-"
            val unattr = r.result.scalars["pillar_damage_unattributed"] ?: 0.0
            for ((team, v) in teams) {
                val o = v as kotlinx.serialization.json.JsonObject
                fun num(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                val left = ((o["pillar_hp_left"] as? kotlinx.serialization.json.JsonObject)?.get("sum") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                appendLine("| ${r.run} | ${r.seed} | $endKind | $decidedBy | $team | ${fmt(num("pillar_damage_dealt"))} | ${fmt(num("pillar_damage_taken"))} | ${fmt(left)} | ${fmt(unattr)} |")
            }
        }
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
}
