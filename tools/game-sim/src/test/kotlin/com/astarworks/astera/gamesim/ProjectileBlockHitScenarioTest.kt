package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-14: a plain projectile (the simple-shoot family) meets a block the way v1 did. It ends at the exact face,
 * it never reaches an entity standing behind the wall, a fast shot does not tunnel a thin wall, the impact sound and
 * particles play at the face, and the skill's chip damage reaches the block.
 */
class ProjectileBlockHitScenarioTest {

    private class Bursts : ISkillParticleGateway {
        val all = ArrayList<ParticleBurst>()
        override fun emit(burst: ParticleBurst) { all += burst }
    }

    @Test
    fun `pistol shot stops at the wall, plays the impact at the face and chips the block`() {
        val behind = simVictim(1)
        val bursts = Bursts()
        val sim = CastSim(mapOf(behind to Vec3(0.0, 0.0, 9.0)), blockAtZ = 6.0, halfWidth = 0.3, boxHeight = 2.0, particleGateway = bursts)
        sim.cast("pistol-shot", 30)
        assertThat(sim.dealt.filter { it.victim == behind }).describedAs("nothing behind the wall is hit").isEmpty()
        assertThat(sim.blockStrikes).describedAs("block chip damage").isNotEmpty()
        assertThat(sim.sounds.map { it.second }).contains("astera:weapons/gun-impact-block")
        assertThat(bursts.all.maxOf { it.at.z }).describedAs("no particle past the face").isLessThanOrEqualTo(6.0 + 1e-6)
        assertThat(bursts.all.any { kotlin.math.abs(it.at.z - 6.0) < 1e-6 }).describedAs("impact particles at the face").isTrue()
    }

    @Test
    fun `sniper shot at 6 blocks per tick does not tunnel a wall`() {
        val behind = simVictim(2)
        val sim = CastSim(mapOf(behind to Vec3(0.0, 0.0, 30.0)), blockAtZ = 20.0, halfWidth = 0.3, boxHeight = 2.0)
        sim.cast("sniper-shot", 30)
        assertThat(sim.dealt.filter { it.victim == behind }).isEmpty()
        assertThat(sim.sounds.map { it.second }).contains("astera:weapons/gun-impact-block")
    }

    @Test
    fun `a wall closer than the muzzle step still stops the shot (first segment is probed)`() {
        val behind = simVictim(3)
        val sim = CastSim(mapOf(behind to Vec3(0.0, 0.0, 4.0)), blockAtZ = 1.5, halfWidth = 0.3, boxHeight = 2.0)
        sim.cast("pistol-shot", 20)
        assertThat(sim.dealt.filter { it.victim == behind }).isEmpty()
        assertThat(sim.blockStrikes).isNotEmpty()
    }

    @Test
    fun `an entity in front of the wall is still hit and a shot without a wall reaches its target`() {
        val front = simVictim(4)
        val walled = CastSim(mapOf(front to Vec3(0.0, 0.0, 4.0)), blockAtZ = 6.0, halfWidth = 0.3, boxHeight = 2.0)
        walled.cast("pistol-shot", 30)
        assertThat(walled.dealt.filter { it.victim == front }).isNotEmpty()

        val open = CastSim(mapOf(front to Vec3(0.0, 0.0, 9.0)), halfWidth = 0.3, boxHeight = 2.0)
        open.cast("pistol-shot", 30)
        assertThat(open.dealt.filter { it.victim == front }).isNotEmpty()
        assertThat(open.sounds.map { it.second }).describedAs("hit sound plays on an entity hit").contains("astera:weapons/gun-impact")
    }

    @Test
    fun `every shipped plain projectile weapon is stopped by a wall`() {
        for (id in listOf("pistol-shot", "rifle-shot", "revolver-shot", "smg-burst", "shortbow-arrow", "longbow-arrow", "hand-crossbow-bolt", "thorn-bolt", "throwing-knife", "fireball-cast", "frost-shard")) {
            val behind = simVictim(9)
            val sim = CastSim(mapOf(behind to Vec3(0.0, 0.0, 12.0)), blockAtZ = 8.0, halfWidth = 0.3, boxHeight = 2.0)
            sim.cast(id, 60)
            assertThat(sim.dealt.filter { it.victim == behind }).describedAs("$id behind a wall").isEmpty()
        }
    }
}
