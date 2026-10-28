package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * RC1 / RC2 / RC8 gate: what the client would show for the displays a skill writes, reconstructed from the exact fields the
 * Paper gateway sends ([RecordingVisuals] client model).
 *  (a) a display gets at most ONE write per tick (split position / rotation / scale writes desynchronise);
 *  (b) rotation and scale interpolate over the same length as the position (a lagging rotation swings the model off its hand);
 *  (c) a hand-anchored display stays rides the caster (no teleport lag, bounded reach) while the caster dashes (bastion-greatsword-s1);
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
        val laggy = rec.calls.filter { it.write.anchor == null && it.write.interpolationDuration != it.write.teleportDuration || it.write.interpolationDelay != 0 }
        assertThat(laggy.map { "h${it.handle} t${it.tick}" }).describedAs("rotation/scale interpolation differs from position").isEmpty()
    }

    @Test
    fun `a hand anchored display stays on the hand while the caster dashes`() {
        val (_, rec, feet) = run("bastion-greatsword-s1", 60)
        val anchored = rec.calls.filter { it.write.anchor != null }.map { it.handle }.toSet()
        assertThat(anchored).describedAs("the hand sword is anchored to the caster").isNotEmpty
        assertThat(feet.map { it.second }.distinct().size).describedAs("the caster actually dashed").isGreaterThan(3)
        // A rider never teleports (v1: passenger of the caster): every anchored write moves with the caster instantly, so a dash cannot leave it behind.
        val lagging = rec.calls.filter { it.write.anchor != null && it.write.teleportDuration != 0 }
        assertThat(lagging.map { "h${it.handle} t${it.tick}" }).describedAs("anchored writes that would lag behind the dashing caster").isEmpty()
        // Its hand-relative position is the authored rig (the animated hand sweeps the sword), identical wherever the caster stands.
        for (h in anchored) for ((t, f) in feet) {
            val shown = rec.shownAt(h, t, f) ?: continue
            assertThat(shown.distance(f)).describedAs("h$h t$t rider offset").isLessThan(HAND_REACH)
        }
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
        const val HAND_REACH = 6.0
    }
}
