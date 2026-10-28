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
 *  (d) viewer-facing plates are a vanilla billboard, never a server-side aim; a CENTER billboard keeps only the element's own
 *      roll as `left_rotation` (v1 VfxTimeline.kt:362-366), so it is a pure rotation about the display's z (R3);
 *  (e) every text quad, solid or glyph, is shifted by the native one-line text centre (v1 VfxQuad.kt:59-61), so its box centre
 *      sits on the entity position (R4).
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
        // A rider never teleports: v1 PaperDisplayEntityGateway.spawnAttached (addPassenger, :83) and teleport() (:293-299) fold the
        // offset into the transform and never set teleportDuration for an attachment (:303 is the non-attached branch).
        val lagging = rec.calls.filter { it.write.anchor != null && it.write.teleportDuration != 0 }
        assertThat(lagging.map { "h${it.handle} t${it.tick}" }).describedAs("anchored writes that would lag behind the dashing caster").isEmpty()
        // The offset a write carries is measured from the caster's feet AT THE WRITE'S OWN TICK (a stale anchor sends the display one
        // dash step behind), and what the client shows at every tick is exactly caster feet now + that authored offset.
        val feetByTick = feet.toMap()
        val stale = ArrayList<String>()
        for (c in rec.calls) {
            val anchor = c.write.anchor ?: continue
            val now = feetByTick[c.tick - 1] /* call at sim tick T sees feet after step T = list index T-1 */ ?: continue
            if (anchor.feet.distance(now) > EXACT) stale += "h${c.handle} t${c.tick} anchor ${anchor.feet.distance(now)} behind"
        }
        assertThat(stale).describedAs("anchored writes measured from a stale caster position").isEmpty()
        val drift = ArrayList<String>()
        for (h in anchored) for ((t, f) in feet) {
            val write = rec.calls.lastOrNull { it.handle == h && it.tick <= t } ?: continue
            val anchor = write.write.anchor ?: continue
            val authored = write.write.at - anchor.feet
            val shown = (rec.shownAt(h, t, f) ?: continue) - f
            if (shown.distance(authored) > EXACT) drift += "h$h t$t off the hand by ${shown.distance(authored)}"
        }
        assertThat(drift).describedAs("anchored display position relative to its caster differs from the authored offset").isEmpty()
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
                val q = c.write.transform.rotation
                if (c.write.billboard == com.astarworks.astera.core.domain.effect.DisplayBillboard.CENTER && (kotlin.math.abs(q.x) > 1e-6 || kotlin.math.abs(q.y) > 1e-6)) {
                    bad += "$id h${c.handle} t${c.tick} CENTER billboard rolls about a non-z axis (${q.x}, ${q.y})"
                }
                if (c.write.anchor != null) bad += "$id h${c.handle} t${c.tick} billboard ${c.write.billboard} anchored"
            }
        }
        assertThat(bad).isEmpty()
    }

    @Test
    fun `a center billboard keeps the track roll like v1`() {
        // couture-rapier-s1 rotates its petals / cuts about the view axis (v1 dump: 81 rolled center texts across the roster).
        val (_, rec, _) = run("couture-rapier-s1", 90)
        val rolled = rec.calls.filter {
            it.write.billboard == com.astarworks.astera.core.domain.effect.DisplayBillboard.CENTER && kotlin.math.abs(it.write.transform.rotation.w) < 0.999
        }
        assertThat(rolled).describedAs("center billboard writes carrying a roll").isNotEmpty
    }

    @Test
    fun `text quads sit on their entity position like v1`() {
        val bad = ArrayList<String>()
        for (skill in listOf("couture-rapier-s1", "couture-rapier-def", "gossamer-katana-s1", "bastion-greatsword-ego", "orrery-s2", "vendetta-rifle-ego")) {
            val (_, rec, _) = run(skill, 90)
            for (c in rec.calls) {
                if (rec.lives[c.handle]?.kind != "quad") continue
                val t = c.write.transform
                val want = t.rotation.rotate(Vec3(-0.0125 * t.scale.x, -0.125 * t.scale.y, 0.0))
                if (t.translation.distance(want) > 1e-6) bad += "$skill h${c.handle} t${c.tick} translation ${t.translation} want $want"
            }
        }
        assertThat(bad).describedAs("quads whose box centre is off the entity position").isEmpty()
    }

    private companion object {
        const val EXACT = 1e-6
    }
}
