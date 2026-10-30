package com.astarworks.astera.gamesim.skillengine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt

/**
 * Caster-view clutter rule (se-nearcam). A skill must not fill the caster's own first-person screen: at no tick of any recorded pose may the EXPECTED number
 * of vanilla particles alive in the first-person view volume exceed [LIMIT]: a forward cone ([HALF_ANGLE_DEG] deg half-angle along the row's look) out to
 * [RADIUS] blocks from the eye, excluding anything below eye - [FLOOR_BELOW_EYE] (ground rings/AoE discs at the feet). Rows come from the expanded golden
 * recording (`@tick particle - op=.. type=.. count=N at=x,y,z .. size=..`; the caster stands at the origin, eye at y = [EYE_Y]); a content particle id
 * resolves to its `vanilla_particle` in content/particles.
 *
 * Expected alive at tick t = sum over spawn rows at s <= t of `count * P(life > t - s)` (a particle spawned at s is drawn on ticks s .. s+life-1), counting
 * only the ticks at which it is inside the volume. There is no lingering/short cut: a short-lived particle weighs 1 on its first ticks and its survival
 * falls to 0 at its vanilla maximum, a crit burst of any size is counted. [survival] holds each type's lifetime distribution, read from the 26.3 client jar
 * (`javap -c -p -constants` on the particle classes; `Particle.lifetime` is set in each constructor / provider, U = `random.nextFloat()` uniform [0,1)).
 * Types the jar does not give a lifetime for are listed at [survival].
 */
internal object CasterEyeClutter {
    const val RADIUS = 1.5
    const val HALF_ANGLE_DEG = 70.0
    const val FLOOR_BELOW_EYE = 0.9
    const val EYE_Y = 1.62
    const val LIMIT = 10

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
                            // the caster never sees an owner-hidden row (viewers filter), so it cannot fill the caster's screen
                            if (" viewers=owner-hidden" in l) continue
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

    /** P(floor(a / (slope*U + c)) + off > k): the `(int)(a / (slope * U + c)) + off` lifetime family (U uniform [0,1)). */
    private fun invLife(a: Double, slope: Double, c: Double, off: Int, k: Int): Double {
        val need = k - off + 1                       // floor(v) >= need  <=>  v >= need
        if (need <= 0) return 1.0
        return ((a / need - c) / slope).coerceIn(0.0, 1.0)  // a / (slope*U + c) >= need  <=>  U <= (a/need - c)/slope
    }

    /** P(L > k) for L uniform on the integers lo..hi (`lo + random.nextInt(hi - lo + 1)`). */
    private fun uniformLife(lo: Int, hi: Int, k: Int): Double = when {
        k < lo -> 1.0
        k >= hi -> 0.0
        else -> (hi - k).toDouble() / (hi - lo + 1)
    }

    /**
     * Survival function P(client lifetime > k) of [vanilla] (k = ticks since spawn; [scale] = the dust `size`). Every line cites the 26.3 client class.
     *  - crit / enchanted_hit: CritParticle `<init>` `max((int)(6.0 / (U*0.8 + 0.6)), 1)` (4..10, mean 5.84). enchanted_hit = CritParticle$MagicProvider.
     *  - damage_indicator: CritParticle$DamageIndicatorProvider `setLifetime(20)`.
     *  - dust / dust_color_transition: DustParticleBase `lifetime = (int) max((float)(int)(8.0 / (nextDouble*0.8 + 0.2)) * scale, 1.0f)`.
     *  - smoke / white_smoke: BaseAshSmokeParticle `max((int)(8 / (U*0.8 + 0.2) * scale), 1)`, scale 1 (SmokeParticle$Provider / WhiteSmokeParticle$Provider `fconst_1`);
     *    large_smoke: LargeSmokeParticle passes scale 2.5 to SmokeParticle.
     *  - snowflake: SnowflakeParticle `(int)(16.0 / (U*0.8 + 0.2)) + 2`. flame / small_flame: RisingParticle `(int)(8.0 / (U*0.8 + 0.2)) + 4` (FlameParticle).
     *  - lava: LavaParticle `(int)(16.0 / (U*0.8 + 0.2))`; its `tick()` also removes it early at random, so this is an upper bound (never a lower count).
     *  - happy_villager: SuspendedTownParticle `(int)(20.0 / (U*0.8 + 0.2))`. composter: SuspendedTownParticle$ComposterFillProvider `setLifetime(3 + nextInt(5))`.
     *  - poof: ExplodeParticle `(int)(16.0 / (U*0.8 + 0.2)) + 2`. explosion: HugeExplosionParticle `6 + nextInt(4)`.
     *  - cloud: PlayerCloudParticle `max((float)(int)(8.0 / (U*0.8 + 0.3)) * 2.5, 1)`.
     *  - sweep_attack: AttackSweepParticle `lifetime = 4`. heart: HeartParticle `16`. end_rod: EndRodParticle `60 + nextInt(12)`.
     *  - firework: FireworkParticles$SparkParticle `48 + nextInt(12)`. electric_spark: GlowParticle$ElectricSparkProvider `setLifetime(nextInt(2) + 2)` (2..3).
     *  - entity_effect: SpellParticle `(int)(8.0 / (U*0.8 + 0.2))`.
     *  - block_crumble: TerrainParticle sets none, so the Particle base `(int)(4.0 / (U*0.9 + 0.1))`. cherry_leaves: FallingParticle `lifetime = 300`.
     * NOT in the jar as a constant: `trail` (TrailParticleOption.duration() comes from the command / row, not the class) and any id no particle def resolves to;
     * both are counted at a flat 16 ticks (the old table value) until the row carries the duration.
     */
    fun survival(vanilla: String, scale: Double?, k: Int): Double = when (vanilla) {
        "crit", "enchanted_hit" -> if (k < 1) 1.0 else invLife(6.0, 0.8, 0.6, 0, k)
        "damage_indicator" -> if (k < 20) 1.0 else 0.0
        "dust", "dust_color_transition" -> {
            // floor(8/x) * scale >= k + 1  <=>  floor(8/x) >= ceil((k + 1) / scale)
            val sc = (scale ?: 1.0).coerceAtLeast(1e-6)
            if (k < 1) 1.0 else invLife(8.0, 0.8, 0.2, 0, kotlin.math.ceil((k + 1) / sc).toInt() - 1)
        }
        "smoke", "white_smoke" -> if (k < 1) 1.0 else invLife(8.0, 0.8, 0.2, 0, k)
        "large_smoke" -> if (k < 1) 1.0 else invLife(8.0 * 2.5, 0.8, 0.2, 0, k)
        "snowflake", "poof" -> invLife(16.0, 0.8, 0.2, 2, k)
        "flame", "small_flame" -> invLife(8.0, 0.8, 0.2, 4, k)
        "lava" -> invLife(16.0, 0.8, 0.2, 0, k)
        "happy_villager" -> invLife(20.0, 0.8, 0.2, 0, k)
        "entity_effect" -> invLife(8.0, 0.8, 0.2, 0, k)
        "composter" -> uniformLife(3, 7, k)
        "explosion" -> uniformLife(6, 9, k)
        "cloud" -> if (k < 1) 1.0 else invLife(8.0, 0.8, 0.3, 0, kotlin.math.ceil((k + 1) / 2.5).toInt() - 1)
        "sweep_attack" -> if (k < 4) 1.0 else 0.0
        "heart" -> if (k < 16) 1.0 else 0.0
        "end_rod" -> uniformLife(60, 71, k)
        "firework" -> uniformLife(48, 59, k)
        "electric_spark" -> uniformLife(2, 3, k)
        "block_crumble" -> invLife(4.0, 0.9, 0.1, 0, k)
        "cherry_leaves" -> if (k < 300) 1.0 else 0.0
        else -> if (k < 16) 1.0 else 0.0
    }

    /** First k at which [survival] is 0 (the particle is gone); capped so a bad row cannot loop. */
    private fun maxLife(vanilla: String, scale: Double?): Int {
        var k = 1
        while (k < 400 && survival(vanilla, scale, k) > 0.0) k++
        return k
    }

    private val ROW = Regex("^@(\\d+) particle \\S+ op=(\\S+) type=(\\S+)(?: count=(\\d+))? at=(\\S+),(\\S+),(\\S+) .* size=(\\S+) .*look=(\\S+),(\\S+),(\\S+)")
    private val COS_HALF = kotlin.math.cos(Math.toRadians(HALF_ANGLE_DEG))

    private data class Def(val vanilla: String, val scale: Double?, val count: Int)

    private fun defs(particlesDir: Path): Map<String, Def> {
        val out = HashMap<String, Def>()
        Files.list(particlesDir).use { s ->
            s.filter { it.toString().endsWith(".yaml") }.forEach { f ->
                val text = Files.readString(f)
                val vp = Regex("(?m)^vanilla_particle:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: return@forEach
                val scale = Regex("scale:\\s*([0-9.]+)").find(text)?.groupValues?.get(1)?.toDouble()
                val count = Regex("(?m)^count:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 1
                out[f.fileName.toString().removeSuffix(".yaml")] = Def(vp, scale, count)
            }
        }
        return out
    }

    /**
     * One problem kind per pose: the largest expected alive count (see the class doc) over all ticks, flagged when above [LIMIT]. A particle's drift after
     * spawn is ignored except for directed rows (`count=0`: the offset is the velocity direction and `speed` its length per tick; vanilla count 0 sets
     * velocity = offset * speed, friction ignored = an upper bound on the path), so a ring spawned at the target that travels back at the caster is seen.
     */
    fun check(key: String, lines: List<String>, particlesDir: Path): List<String> {
        val defs = defs(particlesDir)
        val problems = ArrayList<String>()
        var pose = ""
        // alive[t] = expected count alive at tick t inside the volume; byName[t] = the same split by row name
        val alive = java.util.TreeMap<Int, Double>()
        val byName = HashMap<Int, HashMap<String, Double>>()
        fun flush() {
            if (alive.isNotEmpty()) {
                val worst = alive.entries.maxByOrNull { it.value }!!
                if (worst.value > LIMIT) {
                    val top = byName[worst.key]!!.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key} x${"%.1f".format(it.value)}" }
                    problems += "$key [$pose] @${worst.key}: ${"%.1f".format(worst.value)} particles EXPECTED ALIVE in the first-person view volume (cone ${HALF_ANGLE_DEG.toInt()} deg, $RADIUS blocks, above eye-$FLOOR_BELOW_EYE; each spawn weighed by its vanilla lifetime survival) (limit $LIMIT): $top"
                }
            }
            alive.clear(); byName.clear()
        }
        for (l in lines) {
            if (l.startsWith("## ")) { flush(); pose = l.removePrefix("## "); continue }
            val m = ROW.find(l) ?: continue
            val tick = m.groupValues[1].toInt()
            val x = m.groupValues[5].toDouble(); val y = m.groupValues[6].toDouble(); val z = m.groupValues[7].toDouble()
            val lx = m.groupValues[9].toDouble(); val ly = m.groupValues[10].toDouble(); val lz = m.groupValues[11].toDouble()
            val id = m.groupValues[3]
            val def = defs[id]
            val vanilla = def?.vanilla ?: id
            val scale = m.groupValues[8].toDoubleOrNull() ?: def?.scale
            fun inView(px: Double, py: Double, pz: Double): Boolean {
                val ey = py - EYE_Y
                val d = sqrt(px * px + ey * ey + pz * pz)
                if (d > RADIUS || ey < -FLOOR_BELOW_EYE) return false
                val ll = sqrt(lx * lx + ly * ly + lz * lz)
                return !(d > 1e-6 && ll > 1e-6 && (px * lx + ey * ly + pz * lz) / (d * ll) < COS_HALF)
            }
            val directed = m.groupValues[4] == "0"
            val offM = Regex(" off=(\\S+),(\\S+),(\\S+)").find(l); val spM = Regex(" speed=(\\S+)").find(l)
            // A content-particle row carries no count: the def's own count is what spawns (a 5-flame burst used to be counted as 1).
            val count = maxOf(1, m.groupValues[4].toIntOrNull() ?: def?.count ?: 1)
            val name = "${m.groupValues[2].replace(Regex("\\[\\d+]"), "[]")}($vanilla)"
            for (k in 0 until maxLife(vanilla, scale)) {
                var px = x; var py = y; var pz = z
                if (directed && offM != null && spM != null) {
                    val sp = spM.groupValues[1].toDoubleOrNull() ?: 0.0
                    px += offM.groupValues[1].toDouble() * sp * k; py += offM.groupValues[2].toDouble() * sp * k; pz += offM.groupValues[3].toDouble() * sp * k
                }
                if (!inView(px, py, pz)) continue
                val w = count * survival(vanilla, scale, k)
                alive.merge(tick + k, w, Double::plus)
                byName.getOrPut(tick + k) { HashMap() }.merge(name, w, Double::plus)
            }
        }
        flush()
        return problems
    }
}
