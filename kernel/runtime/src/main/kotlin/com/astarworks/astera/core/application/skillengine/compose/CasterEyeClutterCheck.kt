package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.compose.drycast.ClutterContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.CasterEyeCaps
import com.astarworks.astera.core.domain.skillengine.model.CasterPose
import com.astarworks.astera.core.domain.skillengine.model.drycast.DryCastTrace
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import java.util.TreeMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Load-time check 16 (engine-gaps-v2 section 1): a skill must not fill the caster's own first-person screen. Reads the [DryCastTrace] of a
 * dry cast in each configured caster pose (the caster at the origin, eye [CasterEyeCaps.eyeHeight]) and applies the survival-weighted model:
 *
 *  1. Particles with a mean client lifetime of at least [CasterEyeCaps.lingerTicks] count as lingering. Each is alive `round(mean)` ticks from
 *     its spawn; a directed one (`count` 0: offset * speed is its velocity) flies on along that path (friction ignored, an upper bound).
 *     It is on screen at the ticks its position lies in the view volume (forward cone [CasterEyeCaps.halfAngleDeg], out to [CasterEyeCaps.radius],
 *     above eye - [CasterEyeCaps.floorBelowEye]). More than [CasterEyeCaps.limit] spawned in the volume at one tick, or alive in it at once, fails.
 *  2. Block / item displays: per tick the sum over live ones in the cone (out to [CasterEyeCaps.displayRange]) of the product of the two largest
 *     scale components over max(distance, 0.5)^2; above [CasterEyeCaps.displayCoverageLimit] for [CasterEyeCaps.displaySustainTicks] ticks fails.
 *
 * A vanilla particle with no entry in `particle-lifetimes.yaml` is an error, never a guess.
 */
internal object CasterEyeClutterCheck {
    fun run(traces: List<Pair<CasterPose, DryCastTrace>>, caps: CasterEyeCaps, ctx: ClutterContext): SkillDecodeError? {
        for ((pose, trace) in traces) {
            particles(pose, trace, caps, ctx)?.let { return it }
            displays(pose, trace, caps)?.let { return it }
        }
        return null
    }

    private class Spawn(val tick: Int, val count: Int, val last: Int, val inside: List<Int>, val spawnInside: Boolean, val name: String)

    private fun row(src: SrcPath?): String = (src?.value ?: "?").replace(Regex("\\[\\d+]"), "[]")

    private fun particles(pose: CasterPose, trace: DryCastTrace, caps: CasterEyeCaps, ctx: ClutterContext): SkillDecodeError? {
        val cosHalf = cos(Math.toRadians(caps.halfAngleDeg))
        val spawns = ArrayList<Spawn>()
        for (s in trace.particles) {
            val def = ctx.particleDefs[s.particle]
            val vanilla = (def?.vanilla ?: s.particle).removePrefix("minecraft:")
            val size = s.size ?: def?.scale
            val life = ctx.lifetimes.meanFor(vanilla, size)
                ?: return SkillDecodeError.UnknownParticleLifetime(vanilla, row(s.src))
            if (life < caps.lingerTicks) continue
            fun inView(px: Double, py: Double, pz: Double): Boolean {
                val ey = py - caps.eyeHeight
                val d = sqrt(px * px + ey * ey + pz * pz)
                if (d > caps.radius || ey < -caps.floorBelowEye) return false
                val ll = sqrt(s.look.x * s.look.x + s.look.y * s.look.y + s.look.z * s.look.z)
                return !(d > 1e-6 && ll > 1e-6 && (px * s.look.x + ey * s.look.y + pz * s.look.z) / (d * ll) < cosHalf)
            }
            val n = Math.round(life).toInt()
            val directed = s.count == 0
            val inside = ArrayList<Int>()
            for (k in 0 until n) {
                var px = s.pos.x; var py = s.pos.y; var pz = s.pos.z
                if (directed) {
                    px += s.offset.x * s.speed * k; py += s.offset.y * s.speed * k; pz += s.offset.z * s.speed * k
                }
                if (inView(px, py, pz)) inside += s.tick + k
            }
            val spawnInside = inView(s.pos.x, s.pos.y, s.pos.z)
            if (inside.isEmpty() && !spawnInside) continue
            // a content-particle row carries no count: the definition's own count is what spawns
            val count = max(1, s.count ?: def?.count ?: 1)
            spawns += Spawn(s.tick, count, s.tick + n - 1, inside, spawnInside, "${row(s.src)}($vanilla)")
        }
        if (spawns.isEmpty()) return null
        fun top(from: List<Spawn>): List<String> =
            from.groupBy { it.name }.mapValues { e -> e.value.sumOf { it.count } }.entries.sortedByDescending { it.value }.take(4).map { "${it.key} x${it.value}" }

        val perTick = TreeMap<Int, Int>()
        for (sp in spawns) if (sp.spawnInside) perTick.merge(sp.tick, sp.count, Int::plus)
        for ((tick, n) in perTick) if (n > caps.limit) {
            return SkillDecodeError.CasterEyeClutter(pose.label, tick, n, caps.limit, alive = false, rows = top(spawns.filter { it.tick == tick && it.spawnInside }))
        }
        var worst = -1
        var worstN = 0
        for (t in 0..spawns.maxOf { it.last }) {
            val n = spawns.filter { t in it.inside }.sumOf { it.count }
            if (n > worstN) { worstN = n; worst = t }
        }
        if (worstN > caps.limit) {
            return SkillDecodeError.CasterEyeClutter(pose.label, worst, worstN, caps.limit, alive = true, rows = top(spawns.filter { worst in it.inside }))
        }
        return null
    }

    private class Live(val src: SrcPath?, var pos: Vec3?, var scale: Vec3?)

    private fun displays(pose: CasterPose, trace: DryCastTrace, caps: CasterEyeCaps): SkillDecodeError? {
        if (trace.displays.isEmpty()) return null
        val yr = Math.toRadians(pose.yawDeg)
        val pr = Math.toRadians(pose.pitchDeg)
        val lx = -kotlin.math.sin(yr) * cos(pr)
        val ly = -kotlin.math.sin(pr)
        val lz = cos(yr) * cos(pr)
        val cosHalf = cos(Math.toRadians(caps.halfAngleDeg))
        val byTick = trace.displays.groupBy { it.tick }
        val lastTick = byTick.keys.max()
        val alive = HashMap<Long, Live>()
        val cov = DoubleArray(lastTick + caps.displaySustainTicks + 1)
        val who = arrayOfNulls<String>(cov.size)
        for (t in 0..lastTick) {
            for (e in byTick[t].orEmpty()) {
                if (!e.alive) { alive.remove(e.id); continue }
                if (e.spawn) {
                    if (e.kind != "block" && e.kind != "item") continue
                    alive[e.id] = Live(e.src, null, null)
                }
                val cur = alive[e.id] ?: continue
                if (e.pos != null && e.scale != null) { cur.pos = e.pos; cur.scale = e.scale }
            }
            var tot = 0.0
            var top = 0.0
            var topRow = ""
            for (v in alive.values) {
                val pos = v.pos ?: continue
                val sc = v.scale ?: continue
                val y = pos.y - caps.eyeHeight
                val dist = sqrt(pos.x * pos.x + y * y + pos.z * pos.z)
                if (dist > caps.displayRange || y < -caps.floorBelowEye) continue
                if (dist > 1e-6 && (pos.x * lx + y * ly + pos.z * lz) / dist < cosHalf) continue
                val s = listOf(sc.x, sc.y, sc.z).sortedDescending()
                val c = s[0] * s[1] / max(dist, 0.5).let { it * it }
                tot += c
                if (c > top) { top = c; topRow = row(v.src) }
            }
            cov[t] = tot
            who[t] = topRow
        }
        for (t in 0..lastTick) {
            val window = (0 until caps.displaySustainTicks).minOf { cov[t + it] }
            if (window > caps.displayCoverageLimit) {
                return SkillDecodeError.DisplayCoverage(pose.label, t, window, caps.displayCoverageLimit, caps.displaySustainTicks, listOfNotNull(who[t]))
            }
        }
        return null
    }
}
