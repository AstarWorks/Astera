package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.DefaultContactResolver
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.hit.RehitWindow
import com.astarworks.astera.core.domain.skillengine.hit.HitWindowTracker
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * SKL-08 W2: the narrow phase of skill hit volumes (capsule / sphere / sector vs a player-sized
 * hitbox), contact point + normal (F-4), victim-frame conversion (F-5) and hit windows.
 */
class HitContactScenarioTest {
    private val resolver = DefaultContactResolver()
    private val eps = Offset.offset(1e-6)

    private fun dummy(feet: Vec3, yaw: Double = 0.0, velocity: Vec3 = Vec3.ZERO): HitCandidate = HitCandidate(
        entityId = UUID.randomUUID(),
        feet = feet,
        yawDeg = yaw,
        box = HitBox(Vec3(feet.x - 0.3, feet.y, feet.z - 0.3), Vec3(feet.x + 0.3, feet.y + 1.8, feet.z + 0.3)),
        eyeHeight = 1.62,
        velocity = velocity,
    )

    private fun narrow(volume: ResolvedVolume, vararg c: HitCandidate, vel: Vec3 = Vec3(0.0, 0.0, 1.7), tick: Int = 5) =
        resolver.narrow("throw_hit", tick, "blade_a", volume, vel, c.toList())

    @Test
    fun `capsule piece flying into a dummy hits with an outward normal and a victim-local point`() {
        val d = dummy(Vec3(0.0, 0.0, 6.0), yaw = 90.0)
        // coin-2 piece: root -> tip along +z, tip 0.1 inside the box front face (z = 5.7), radius 0.22.
        val vol = ResolvedVolume.Capsule(Vec3(0.2, 1.0, 4.8), Vec3(0.2, 1.0, 5.8), 0.22)
        val hit = narrow(vol, d).single()
        assertThat(hit.victim).isEqualTo(d.entityId)
        assertThat(hit.pointWorld.z).isCloseTo(5.7, eps)
        assertThat(hit.pointWorld.x).isCloseTo(0.2, eps)
        // normal points from the victim axis (x=0,z=6) to the point: mostly -z, some +x
        assertThat(hit.normalWorld.length()).isCloseTo(1.0, eps)
        assertThat(hit.normalWorld.z).isLessThan(0.0)
        assertThat(hit.normalWorld.x).isGreaterThan(0.0)
        // pointLocal = rotate(-yaw) * (world - feet), invariant while the victim moves with its yaw
        val moved = dummy(Vec3(10.0, 0.0, -3.0), yaw = 90.0)
        val shifted = ResolvedVolume.Capsule(Vec3(10.2, 1.0, -4.2), Vec3(10.2, 1.0, -3.2), 0.22)
        val hit2 = narrow(shifted, moved).single()
        assertThat(hit2.pointLocal.x).isCloseTo(hit.pointLocal.x, eps)
        assertThat(hit2.pointLocal.y).isCloseTo(hit.pointLocal.y, eps)
        assertThat(hit2.pointLocal.z).isCloseTo(hit.pointLocal.z, eps)
        assertThat(hit.relVelocity).isEqualTo(Vec3(0.0, 0.0, 1.7))
    }

    @Test
    fun `capsule that stays out of reach misses and a long capsule grazing the side hits`() {
        val d = dummy(Vec3(0.0, 0.0, 6.0))
        assertThat(narrow(ResolvedVolume.Capsule(Vec3(1.0, 1.0, 4.0), Vec3(1.0, 1.0, 5.0), 0.22), d)).isEmpty()
        // passes beside the box at x = 0.4: distance to the face (x = 0.3) is 0.1 <= 0.22
        val graze = narrow(ResolvedVolume.Capsule(Vec3(0.4, 1.0, 4.0), Vec3(0.4, 1.0, 7.0), 0.22), d).single()
        assertThat(graze.pointWorld.x).isCloseTo(0.3, eps)
        assertThat(graze.normalWorld.x).isGreaterThan(0.5) // radial from the centre axis, pointing outward on +x
    }

    @Test
    fun `axis through the victim centre axis falls back to the opposite of the hitter velocity`() {
        val d = dummy(Vec3(0.0, 0.0, 6.0))
        val vol = ResolvedVolume.Capsule(Vec3(0.0, 1.0, 5.0), Vec3(0.0, 1.0, 6.0), 0.2)
        val hit = narrow(vol, d, vel = Vec3(0.0, 0.0, 2.0)).single()
        assertThat(hit.normalWorld.z).isCloseTo(-1.0, eps)
    }

    @Test
    fun `sphere hits only inside its radius and broad phase sphere is centred on the volume`() {
        val d = dummy(Vec3(0.0, 0.0, 0.0))
        assertThat(narrow(ResolvedVolume.Sphere(Vec3(1.0, 1.0, 0.0), 0.69), d)).isEmpty()
        assertThat(narrow(ResolvedVolume.Sphere(Vec3(1.0, 1.0, 0.0), 0.71), d)).hasSize(1)
        val cap = ResolvedVolume.Capsule(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 2.0), 0.5)
        assertThat(cap.broadCenter).isEqualTo(Vec3(0.0, 0.0, 1.0))
        assertThat(cap.broadRadius).isCloseTo(1.5, eps)
    }

    @Test
    fun `sector accepts targets inside the wedge and rejects those behind or too far`() {
        val origin = Vec3(0.0, 1.0, 0.0)
        val sector = ResolvedVolume.Sector(origin, Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0), 90.0, 3.0, 2.0)
        val front = dummy(Vec3(0.0, 0.0, 2.0))
        val behind = dummy(Vec3(0.0, 0.0, -2.0))
        val far = dummy(Vec3(0.0, 0.0, 6.0))
        val side = dummy(Vec3(3.0, 0.0, 0.0)) // 90 deg off forward, outside a 45 deg half angle
        val hits = narrow(sector, front, behind, far, side).map { it.victim }
        assertThat(hits).containsExactly(front.entityId)
    }

    @Test
    fun `hit windows once every-n and per-part`() {
        val d = dummy(Vec3(0.0, 0.0, 6.0))
        val vol = ResolvedVolume.Sphere(Vec3(0.0, 1.0, 5.5), 0.5)
        fun contact(tick: Int, frame: String) =
            resolver.narrow("h", tick, frame, vol, Vec3.ZERO, listOf(d)).single()

        val once = HitWindowTracker(RehitWindow.Once)
        assertThat(once.accept(contact(1, "blade_a"))).isTrue()
        assertThat(once.accept(contact(2, "blade_b"))).isFalse()
        assertThat(once.accept(contact(99, "blade_a"))).isFalse()

        val every = HitWindowTracker(RehitWindow.EveryTicks(4))
        assertThat(every.accept(contact(1, "a"))).isTrue()
        assertThat(every.accept(contact(4, "a"))).isFalse()
        assertThat(every.accept(contact(5, "a"))).isTrue()

        val part = HitWindowTracker(RehitWindow.PerPart)
        assertThat(part.accept(contact(1, "blade_a"))).isTrue()
        assertThat(part.accept(contact(1, "blade_b"))).isTrue()
        assertThat(part.accept(contact(2, "blade_a"))).isFalse()
        part.reset()
        assertThat(part.accept(contact(3, "blade_a"))).isTrue()
    }
}
