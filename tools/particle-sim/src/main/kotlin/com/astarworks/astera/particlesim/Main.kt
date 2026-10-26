package com.astarworks.astera.particlesim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import kotlin.system.exitProcess

/**
 * `particle-sim <skill> [--plan plan.json] [--content dir] [--out dir] [--seed n] [--mc 26.3]`
 *
 * Reads the particle emissions of `build/skill-lint/<skill>/plan.json` (make skill-lint-plan) and writes
 * `build/skill-preview/<skill>/particles.json`.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0].startsWith("-")) {
        System.err.println("usage: particle-sim <skill-id> [--plan <plan.json>] [--content <dir>] [--out <dir>] [--seed <n>] [--mc <version>]")
        exitProcess(2)
    }
    val skill = args[0]
    fun opt(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val plan = Paths.get(opt("--plan") ?: "build/skill-lint/$skill/plan.json").toAbsolutePath()
    val content = Paths.get(opt("--content") ?: "content").toAbsolutePath().normalize()
    val outDir = Paths.get(opt("--out") ?: "build/skill-preview").toAbsolutePath().resolve(skill)
    val seed = opt("--seed")?.toLong() ?: DEFAULT_SEED
    check(Files.isRegularFile(plan)) { "no plan at $plan; run: make skill-lint-plan SKILL=$skill" }

    val emissions = Json.parseToJsonElement(Files.readString(plan)).jsonObject["particles"]?.jsonArray?.map { Emission.parse(it.jsonObject) }
        ?: error("$plan has no \"particles\" (it predates the particle dump; re-run make skill-lint-plan SKILL=$skill)")

    val dist = McDistribution.locate(opt("--mc") ?: McDistribution.DEFAULT_VERSION)
    val rt = McRuntime(dist, seed)
    val catalog = SpriteCatalog(content, dist.clientJar)
    val sim = ParticleSim(rt, catalog)
    val ticks = sim.run(emissions)

    val file = outDir.resolve("particles.json")
    Files.createDirectories(outDir)
    Files.writeString(file, render(skill, seed, dist, ticks, sim, catalog))
    val covered = sim.reports.values.filter { it.fallbackReason == null }
    val fallen = sim.reports.values.filter { it.fallbackReason != null }
    println("particle-sim: $file  (${emissions.size} emissions, ${ticks.size} ticks, ${ticks.sumOf { it.size }} particle-ticks)")
    println("  vanilla classes: " + covered.joinToString { "${it.type} x${it.particles}" + (if (it.skinned) " [pack skin]" else "") }.ifEmpty { "-" })
    println("  fallback (dot):  " + fallen.joinToString { "${it.type} x${it.particles} (${it.fallbackReason})" }.ifEmpty { "-" })
}

private const val DEFAULT_SEED: Long = 0xA57E4AL

private fun render(skill: String, seed: Long, dist: McDistribution, ticks: List<List<Row>>, sim: ParticleSim, catalog: SpriteCatalog): String {
    fun n(v: Double): String = String.format(Locale.ROOT, "%.5f", if (v == 0.0) 0.0 else v)
    fun f(v: Float): String = String.format(Locale.ROOT, "%.4f", v)
    fun str(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"skill\": ").append(str(skill)).append(",\n")
    sb.append("  \"minecraft\": ").append(str(dist.version)).append(",\n")
    sb.append("  \"seed\": ").append(seed).append(",\n")
    sb.append("  \"tickRate\": 20,\n")
    sb.append("  \"frame\": \"caster-local: origin at the feet, +x right, +y up, +z forward (same as plan.json bones)\",\n")
    sb.append("  \"sizeUnit\": \"blocks; half extent of the camera-facing quad (vanilla quadSize)\",\n")
    sb.append("  \"lightUnit\": \"packed light coords: sky << 20 | block << 4 (15728880 = full bright)\",\n")
    sb.append("  \"ticks\": [\n")
    ticks.forEachIndexed { t, rows ->
        sb.append("    {\"tick\": ").append(t).append(", \"particles\": [")
        sb.append(rows.joinToString(",") { r ->
            "{\"type\": ${str(r.type)}, \"pos\": [${n(r.pos[0])}, ${n(r.pos[1])}, ${n(r.pos[2])}], \"size\": ${n(r.size)}, " +
                "\"sprite\": ${str(r.sprite)}, \"rgba\": [${f(r.rgba[0])}, ${f(r.rgba[1])}, ${f(r.rgba[2])}, ${f(r.rgba[3])}], " +
                "\"light\": ${r.light}, \"roll\": ${n(r.roll)}, \"age\": ${r.age}}"
        })
        sb.append("]}").append(if (t < ticks.size - 1) ",\n" else "\n")
    }
    sb.append("  ],\n")
    sb.append("  \"atlas\": {")
    val used = ticks.flatten().map { it.sprite }.toSortedSet()
    sb.append(used.mapNotNull { id -> catalog.source(id)?.let { "\n    ${str(id)}: {\"source\": ${str(it.kind)}, \"path\": ${str(it.path)}}" } }.joinToString(","))
    sb.append("\n  },\n")
    sb.append("  \"coverage\": [")
    sb.append(sim.reports.values.joinToString(",") { r ->
        "\n    {\"type\": ${str(r.type)}, \"emissions\": ${r.emissions}, \"particles\": ${r.particles}, \"mode\": " +
            (if (r.fallbackReason == null) "\"vanilla\"" else "\"fallback\", \"reason\": ${str(r.fallbackReason!!)}") +
            ", \"packSkin\": ${r.skinned}" + (r.partialReason?.let { ", \"partial\": ${str(it)}" } ?: "") + "}"
    })
    sb.append("\n  ]\n}\n")
    return sb.toString()
}

