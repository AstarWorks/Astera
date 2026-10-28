package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * RC1 / RC2 / RC8 gate: what the client would show for the displays a skill writes, reconstructed from the exact fields the
 * Paper gateway sends ([RecordingVisuals] client model).
 *  (a) a display gets at most ONE write per tick (split position / rotation / scale writes desynchronise);
 *  (b) rotation and scale interpolate over the same length as the position (a lagging rotation swings the model off its hand);
 *  (c) a hand-anchored display stays within [HAND_TOLERANCE] blocks of the hand while the caster dashes (bastion-greatsword-s1);
 *  (d) viewer-facing plates are a vanilla billboard, never a server-side aim.
 */
class DisplayClientModelScenarioTest {
    private val origin = Vec3(10.0, 64.0, 10.0)
    private val yaw = 0.0

    private fun run(skill: String, ticks: Int): Triple<CastSim, RecordingVisuals, List<Pair<Int, Vec3>>> {
        lateinit var sim: CastSim
        val rec = RecordingVisuals({ sim.now }, { sim.casterFeet })
        sim = CastSim(emptyMap(), origin = origin, yawDeg = yaw, visualGateway = rec, casterStacks = mapOf("charge" to 5, "bleed" to 5, "burn" to 5))
        sim.start(skill)
        val feet = ArrayList<Pair<Int, Vec3>>()
        for (t in 0 until ticks) { sim.step(t + 1); feet += t to sim.casterFeet }
        return Triple(sim, rec, feet)
    }

    @Test
    fun `every display is one atomic write per tick with matching interpolation`() {
        val (_, rec, _) = run("bastion-greatsword-s1", 60)
        assertThat(rec.calls).isNotEmpty
        val perTick = rec.calls.filter { !it.spawn }.groupBy { it.handle to it.tick }.filterValues { it.size > 1 }
        assertThat(perTick.keys).describedAs("split writes (handle, tick)").isEmpty()
        // a followed effect teleports its position over follow_teleport_ticks while rotation/scale interpolate (v1 VfxPlayer.kt:416-419)
        val laggy = rec.calls.filter { (!it.followed && it.write.interpolationDuration != it.write.teleportDuration) || it.write.interpolationDelay != 0 }
        assertThat(laggy.map { "h${it.handle} t${it.tick}" }).describedAs("rotation/scale interpolation differs from position").isEmpty()
    }

    @Test
    fun `a hand anchored display stays on the hand while the caster dashes`() {
        val (_, rec, feet) = run("bastion-greatsword-s1", 60)
        val anchored = rec.calls.filter { it.write.anchor != null }.map { it.handle }.toSet()
        assertThat(anchored).describedAs("the hand sword is anchored to the caster").isNotEmpty
        assertThat(feet.map { it.second }.distinct().size).describedAs("the caster actually dashed").isGreaterThan(3)
        var worst = 0.0
        for (h in anchored) {
            var prevRel: Vec3? = null
            for ((t, f) in feet) {
                val rel = (rec.shownAt(h, t, f) ?: continue) - f
                // Hand-relative motion per tick is the authored rig only; a display left behind by the dash slides ~1.8 blocks/tick.
                prevRel?.let { worst = maxOf(worst, rel.distance(it)); }
                prevRel = rel
            }
        }
        assertThat(worst).describedAs("max per-tick slide of the anchored display relative to the hand").isLessThan(HAND_TOLERANCE)
    }

    @Test
    fun `viewer facing plates are a vanilla billboard`() {
        val bad = ArrayList<String>()
        for (id in CastSim(emptyMap(), origin = origin).catalog.ids().filter { '@' !in it }.sorted()) {
            lateinit var sim: CastSim
            val rec = RecordingVisuals({ sim.now }, { sim.casterFeet })
            sim = CastSim(emptyMap(), origin = origin, yawDeg = yaw, visualGateway = rec, casterStacks = mapOf("charge" to 5, "bleed" to 5, "burn" to 5))
            runCatching { sim.cast(id, 90) }
            for (c in rec.calls) {
                if (c.write.billboard == com.astarworks.astera.core.domain.effect.DisplayBillboard.FIXED) continue
                if (c.write.transform.rotation.let { kotlin.math.abs(it.w) < 0.999999 }) bad += "$id h${c.handle} t${c.tick} billboard ${c.write.billboard} with a non-identity rotation"
                if (c.write.anchor != null) bad += "$id h${c.handle} t${c.tick} billboard ${c.write.billboard} anchored"
            }
        }
        assertThat(bad).isEmpty()
    }

    private companion object {
        const val HAND_TOLERANCE = 0.3
    }
}
