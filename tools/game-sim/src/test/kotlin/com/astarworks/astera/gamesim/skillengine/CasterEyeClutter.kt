package com.astarworks.astera.gamesim.skillengine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Caster-view clutter rule (se-nearcam). A skill must not fill the caster's own first-person screen: at no tick of any recorded pose may more than
 * [LIMIT] lingering vanilla particles spawn in the first-person view volume: a forward cone ([HALF_ANGLE_DEG] deg half-angle along the row's look) out to [RADIUS] blocks from the eye, excluding anything below eye - [FLOOR_BELOW_EYE] (ground rings/AoE discs at the feet). Rows come from the expanded golden recording
 * (`@tick particle - op=.. type=.. count=N at=x,y,z .. size=..`; the caster stands at the origin, eye at y = [EYE_Y]); a content particle id resolves to its
 * `vanilla_particle` in content/particles.
 *
 * Lingering = mean client lifetime >= [LINGER_TICKS] ticks. Lifetimes are read from the 26.3 client jar (`javap -c` on the particle classes), not guessed:
 * `Particle.lifetime` is set in each constructor from `random.nextFloat()` U:
 * crit / enchanted_hit `6 / (0.8U + 0.6)` (4-10), snowflake `16 / (0.8U + 0.2) + 2` (18-82), flame (RisingParticle) `8 / (0.8U + 0.2) + 4` (12-44),
 * lava `16 / (0.8U + 0.2)` (16-80), happy_villager `20 / (0.8U + 0.2)`, heart 16, end_rod 60-71, firework 48-60, sweep_attack 4,
 * electric_spark (GlowParticle) 2-4, dust / dust_color_transition `8 / (0.8U + 0.2) * scale`; smoke / poof / explosion / cloud families below.
 * The mean of `k / (0.8U + 0.2)` is `k / 0.8 * ln 5`.
 */
internal object CasterEyeClutter {
    const val RADIUS = 1.5
    const val HALF_ANGLE_DEG = 70.0
    const val FLOOR_BELOW_EYE = 0.9
    const val EYE_Y = 1.62
    const val LIMIT = 10
    const val LINGER_TICKS = 6.0

    /** Display coverage: block / item displays only (sprite, text, ribbon and veil scales are not world sizes). Cone as above, out to [DISPLAY_RANGE]. */
    const val DISPLAY_RANGE = 3.0
    const val DISPLAY_COVERAGE_LIMIT = 1.5
    const val DISPLAY_SUSTAIN_TICKS = 4
    private val DISP = Regex("^@(\\d+) (spawn|write|remove) #(\\d+)")
    private val POS = Regex(" pos=(\\S+),(\\S+),(\\S+)")
    private val SCL = Regex(" scale=(\\S+),(\\S+),(\\S+)")
    private val POSE = Regex("yaw=(\\S+) pitch=(\\S+)")

    /**
     * Sum over live block / item displays inside the view volume of (two largest scale components) / max(distance, 0.5)^2 per tick (a rough share of the
     * screen). A skill fails when that sum stays above [DISPLAY_COVERAGE_LIMIT] for [DISPLAY_SUSTAIN_TICKS] consecutive ticks (a one-tick spawn at the
     * muzzle is not a screen fill). The cut sits in the audit gap between 1.34 (the greatsword blade in front of the caster) and 1.73 (the volley fins).
     */
    fun checkDisplays(key: String, lines: List<String>): List<String> {
        val problems = ArrayList<String>()
        var pose = ""
        var ev = ArrayList<String>()
        fun flush() {
            val m = POSE.find(pose)
            if (m != null && ev.isNotEmpty()) {
                val yr = Math.toRadians(m.groupValues[1].toDouble()); val pr = Math.toRadians(m.groupValues[2].toDouble())
                val lx = -kotlin.math.sin(yr) * kotlin.math.cos(pr); val ly = -kotlin.math.sin(pr); val lz = kotlin.math.cos(yr) * kotlin.math.cos(pr)
                val byTick = ev.groupBy { DISP.find(it)!!.groupValues[1].toInt() }
                val alive = HashMap<String, Triple<String, DoubleArray?, DoubleArray?>>()
                val cov = DoubleArray((byTick.keys.max()) + DISPLAY_SUSTAIN_TICKS + 1)
                val who = arrayOfNulls<String>(cov.size)
                for (t in 0..byTick.keys.max()) {
                    for (l in byTick[t].orEmpty()) {
                        val d = DISP.find(l)!!; val id = d.groupValues[3]
                        if (d.groupValues[2] == "remove") { alive.remove(id); continue }
                        if (d.groupValues[2] == "spawn") {
                            val kind = Regex(" kind=(\\S+)").find(l)?.groupValues?.get(1)
                            if (kind != "block" && kind != "item") continue
                            alive[id] = Triple(Regex("op=(\\S+)").find(l)!!.groupValues[1], null, null)
                        }
                        val cur = alive[id] ?: continue
                        val pm = POS.find(l); val sm = SCL.find(l)
                        if (pm != null && sm != null) alive[id] = Triple(cur.first, doubleArrayOf(pm.groupValues[1].toDouble(), pm.groupValues[2].toDouble(), pm.groupValues[3].toDouble()),
                            doubleArrayOf(sm.groupValues[1].toDouble(), sm.groupValues[2].toDouble(), sm.groupValues[3].toDouble()))
                    }
                    var tot = 0.0; var top = 0.0; var topOp = ""
                    for ((_, v) in alive) {
                        val pos = v.second ?: continue; val sc = v.third ?: continue
                        val x = pos[0]; val y = pos[1] - EYE_Y; val z = pos[2]; val dist = sqrt(x * x + y * y + z * z)
                        if (dist > DISPLAY_RANGE || y < -FLOOR_BELOW_EYE) continue
                        if (dist > 1e-6 && (x * lx + y * ly + z * lz) / dist < COS_HALF) continue
                        val s = sc.sortedDescending(); val c = s[0] * s[1] / maxOf(dist, 0.5).let { it * it }
                        tot += c; if (c > top) { top = c; topOp = v.first }
                    }
                    cov[t] = tot; who[t] = topOp
                }
                var t = 0
                while (t < byTick.keys.max() + 1) {
                    val window = (0 until DISPLAY_SUSTAIN_TICKS).minOf { cov[t + it] }
                    if (window > DISPLAY_COVERAGE_LIMIT) {
                        problems += "$key [$pose] @$t: block/item displays cover ~${"%.2f".format(window)} (limit $DISPLAY_COVERAGE_LIMIT, scale^2/distance^2 summed) for $DISPLAY_SUSTAIN_TICKS+ ticks near the caster eye, largest ${who[t]}"
                        break
                    }
                    t++
                }
            }
            ev = ArrayList()
        }
        for (l in lines) {
            if (l.startsWith("## ")) { flush(); pose = l.removePrefix("## "); continue }
            if (DISP.containsMatchIn(l)) ev.add(l)
        }
        flush()
        return problems
    }

    private fun riseMean(k: Double) = k / 0.8 * ln(5.0)

    /** Mean client lifetime in ticks of [vanilla]; [scale] is the dust size. Unknown types count as lingering (a long-ish 16). */
    fun meanLife(vanilla: String, scale: Double?): Double = when (vanilla) {
        "crit", "enchanted_hit" -> 6.9
        "electric_spark" -> 3.0
        "sweep_attack" -> 4.0
        "snowflake", "poof", "explosion" -> 34.0
        "flame" -> 20.0
        "lava" -> 32.0
        "happy_villager" -> 40.0
        "heart" -> 16.0
        "end_rod" -> 65.0
        "firework" -> 54.0
        "cloud" -> 14.0
        "smoke", "large_smoke", "white_smoke", "composter", "entity_effect" -> 16.0
        "dust", "dust_color_transition" -> riseMean(8.0) * (scale ?: 1.0)
        else -> 16.0
    }

    private val ROW = Regex("^@(\\d+) particle \\S+ op=(\\S+) type=(\\S+)(?: count=(\\d+))? at=(\\S+),(\\S+),(\\S+) .* size=(\\S+) .*look=(\\S+),(\\S+),(\\S+)")
    private val COS_HALF = kotlin.math.cos(Math.toRadians(HALF_ANGLE_DEG))

    private fun defs(particlesDir: Path): Map<String, Pair<String, Double?>> {
        val out = HashMap<String, Pair<String, Double?>>()
        Files.list(particlesDir).use { s ->
            s.filter { it.toString().endsWith(".yaml") }.forEach { f ->
                val text = Files.readString(f)
                val vp = Regex("(?m)^vanilla_particle:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: return@forEach
                val scale = Regex("scale:\\s*([0-9.]+)").find(text)?.groupValues?.get(1)?.toDouble()
                out[f.fileName.toString().removeSuffix(".yaml")] = vp to scale
            }
        }
        return out
    }

    /** One problem line per (pose, tick) that puts more than [LIMIT] lingering particles inside the eye sphere. */
    fun check(key: String, lines: List<String>, particlesDir: Path): List<String> {
        val defs = defs(particlesDir)
        val problems = ArrayList<String>()
        var pose = ""
        val perTick = java.util.TreeMap<Int, Int>()
        val ops = HashMap<Int, MutableMap<String, Int>>()
        fun flush() {
            for ((tick, n) in perTick) if (n > LIMIT) {
                val top = ops.getValue(tick).entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key} x${it.value}" }
                problems += "$key [$pose] @$tick: $n lingering particles in the first-person view volume (cone ${HALF_ANGLE_DEG.toInt()} deg, $RADIUS blocks, above eye-$FLOOR_BELOW_EYE) (limit $LIMIT): $top"
            }
            perTick.clear(); ops.clear()
        }
        for (l in lines) {
            if (l.startsWith("## ")) { flush(); pose = l.removePrefix("## "); continue }
            val m = ROW.find(l) ?: continue
            val tick = m.groupValues[1].toInt()
            val x = m.groupValues[5].toDouble(); val y = m.groupValues[6].toDouble() - EYE_Y; val z = m.groupValues[7].toDouble()
            val d = sqrt(x * x + y * y + z * z)
            if (d > RADIUS || y < -FLOOR_BELOW_EYE) continue
            val lx = m.groupValues[9].toDouble(); val ly = m.groupValues[10].toDouble(); val lz = m.groupValues[11].toDouble()
            val ll = sqrt(lx * lx + ly * ly + lz * lz)
            if (d > 1e-6 && ll > 1e-6 && (x * lx + y * ly + z * lz) / (d * ll) < COS_HALF) continue
            val id = m.groupValues[3]
            val (vanilla, defScale) = defs[id] ?: (id to null)
            val scale = m.groupValues[8].toDoubleOrNull() ?: defScale
            if (meanLife(vanilla, scale) < LINGER_TICKS) continue
            val count = maxOf(1, m.groupValues[4].toIntOrNull() ?: 1)
            perTick.merge(tick, count, Int::plus)
            ops.getOrPut(tick) { HashMap() }.merge("${m.groupValues[2].replace(Regex("\\[\\d+]"), "[]")}($vanilla)", count, Int::plus)
        }
        flush()
        return problems
    }
}
