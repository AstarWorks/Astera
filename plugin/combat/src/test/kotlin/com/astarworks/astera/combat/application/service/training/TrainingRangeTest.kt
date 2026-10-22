package com.astarworks.astera.combat.application.service.training

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.port.outbound.ITrainingDummyShells
import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ProjectileOrigin
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.testfixtures.FakeNearbyTargetQuery
import com.astarworks.astera.core.application.port.outbound.lobby.LobbyTrainingRange
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.lobby.LobbyTrainingLayout
import com.astarworks.astera.core.domain.lobby.TrainingDummyKind
import com.astarworks.astera.core.domain.lobby.TrainingDummySpot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerPoseLookup
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** F3-11: dummy HP / DPS / regeneration, the dummy damage path, range protection and the maintenance loop. */
class TrainingRangeTest {

    private val lobby = WorldId("lobby")
    private val plan = LobbyTrainingLayout.plan(0, 0, 64)
    private val range = LobbyTrainingRange(lobby, plan)
    private val attacker = PlayerId(UUID.randomUUID())
    private val source = DamageSource.Projectile(ProjectileId.random(), attacker, ProjectileOrigin.FromSkill(SkillId("pistol-shot")))

    private fun resolved(amount: Double, crit: Boolean = false) =
        ResolvedDamage(finalAmount = amount, attribute = DamageAttribute.FIRE, wasBlocked = false, wasCritical = crit)

    private fun newId() = PlayerId(UUID.randomUUID())

    // ---- TrainingDummy ----------------------------------------------------------------------

    private val spot = TrainingDummySpot("range-10", TrainingDummyKind.RANGE, 10.5, 65.0, 0.5, 90f, distance = 10)

    @Test
    fun `a hit lowers HP, never kills, and bottoms out at 1 HP`() {
        val dummy = TrainingDummy(spot, maxHp = 100.0)
        assertThat(dummy.hit(30.0, tick = 0)).isEqualTo(70.0)
        assertThat(dummy.hit(1000.0, tick = 1)).isEqualTo(TrainingDummy.MIN_HP)
        assertThat(dummy.hp).isEqualTo(1.0)
    }

    @Test
    fun `DPS is the damage of the last 5 seconds per second and old hits fall out of the window`() {
        val dummy = TrainingDummy(spot, maxHp = 1000.0)
        dummy.hit(10.0, 0)
        dummy.hit(30.0, 40)
        assertThat(dummy.dps(now = 50)).isEqualTo(8.0) // 40 damage / 5 s
        assertThat(dummy.dps(now = 110)).isEqualTo(6.0) // the hit at tick 0 is 110 ticks old and gone
        assertThat(dummy.dps(now = 150)).isEqualTo(0.0)
    }

    @Test
    fun `an idle dummy refills after 3 seconds and forgets its DPS`() {
        val dummy = TrainingDummy(spot, maxHp = 100.0)
        dummy.hit(60.0, tick = 10)
        assertThat(dummy.regenerate(now = 69)).isFalse() // 59 ticks idle
        assertThat(dummy.hp).isEqualTo(40.0)
        assertThat(dummy.regenerate(now = 70)).isTrue() // 60 ticks = 3 s
        assertThat(dummy.hp).isEqualTo(100.0)
        assertThat(dummy.dps(now = 70)).isEqualTo(0.0)
        assertThat(dummy.regenerate(now = 200)).isFalse() // nothing to do twice
    }

    @Test
    fun `a new hit restarts the idle timer`() {
        val dummy = TrainingDummy(spot, maxHp = 100.0)
        dummy.hit(10.0, 0)
        dummy.hit(10.0, 50)
        assertThat(dummy.regenerate(now = 100)).isFalse()
        assertThat(dummy.regenerate(now = 110)).isTrue()
    }

    // ---- damage path -------------------------------------------------------------------------

    private class HostGateway(val mob: PlayerId) : IEntityDamageGateway {
        override fun isEntityTarget(target: PlayerId) = target == mob
        override fun apply(target: PlayerId, resolved: ResolvedDamage, source: DamageSource) =
            Result.success(DamageOutcome.HpReduced(7.0))
        override fun hitPoints(target: PlayerId): Double? = 7.0
        override fun heal(target: PlayerId, amount: Double) = Unit
    }

    @Test
    fun `the dummy gateway takes dummy hits on the dummy and leaves other entities to the host`() {
        val registry = TrainingDummyRegistry()
        val dummy = TrainingDummy(spot, maxHp = 100.0)
        registry.register(dummy)
        val body = newId()
        registry.bind(dummy, body)
        val mob = newId()
        val hits = mutableListOf<Pair<TrainingDummy, Double>>()
        val gateway = TrainingDummyEntityDamageGateway(HostGateway(mob), registry, now = { 5L }) { d, r, _ -> hits += d to r.finalAmount }

        assertThat(gateway.isEntityTarget(body)).isTrue()
        assertThat(gateway.isEntityTarget(mob)).isTrue()
        assertThat(gateway.isEntityTarget(newId())).isFalse()

        val outcome = gateway.apply(body, resolved(25.0), source)
        assertThat((outcome as Result.Success).value).isEqualTo(DamageOutcome.HpReduced(75.0))
        assertThat(hits).containsExactly(dummy to 25.0)
        assertThat(gateway.hitPoints(body)).isEqualTo(75.0)
        assertThat(gateway.maxHitPoints(body)).isEqualTo(100.0)
        gateway.heal(body, 500.0)
        assertThat(dummy.hp).isEqualTo(100.0)

        // a mob is the host's business; zero damage on a dummy is immune
        assertThat((gateway.apply(mob, resolved(1.0), source) as Result.Success).value).isEqualTo(DamageOutcome.HpReduced(7.0))
        assertThat(hits).hasSize(1)
        assertThat((gateway.apply(body, resolved(0.0), source) as Result.Failure).error)
            .isInstanceOf(DamageApplicationError.Immune::class.java)
    }

    @Test
    fun `a dummy with no host gateway still works and the host is not required`() {
        val registry = TrainingDummyRegistry()
        val dummy = TrainingDummy(spot, maxHp = 100.0).also(registry::register)
        val body = newId().also { registry.bind(dummy, it) }
        val gateway = TrainingDummyEntityDamageGateway(null, registry, now = { 0L }) { _, _, _ -> }
        assertThat(gateway.isEntityTarget(newId())).isFalse()
        assertThat(gateway.apply(body, resolved(10.0), source).isSuccess).isTrue()
        assertThat((gateway.apply(newId(), resolved(1.0), source) as Result.Failure).error)
            .isInstanceOf(DamageApplicationError.TargetOffline::class.java)
    }

    // ---- range protection --------------------------------------------------------------------

    @Test
    fun `players inside the range are protected, players outside and dummies are not`() {
        val registry = TrainingDummyRegistry()
        val dummy = TrainingDummy(spot).also(registry::register)
        val body = newId().also { registry.bind(dummy, it) }
        val poses = FakePlayerPoseLookup()
        val inside = newId().also { poses.place(it, plan.spawn, world = lobby) }
        val plaza = newId().also { poses.place(it, Vec3(0.5, 65.0, 6.5), world = lobby) }
        val otherWorld = newId().also { poses.place(it, plan.spawn, world = WorldId("world")) }
        poses.place(body, plan.spawn, world = lobby) // a body's pose is known too; it must still be hittable
        val protection = TrainingRangeProtection({ range }, registry, { poses })

        assertThat(protection.isProtected(inside)).isTrue()
        assertThat(protection.isProtected(plaza)).isFalse()
        assertThat(protection.isProtected(otherWorld)).isFalse()
        assertThat(protection.isProtected(body)).isFalse()
        assertThat(protection.isProtected(newId())).isFalse() // not a player
        assertThat(TrainingRangeProtection({ null }, registry, { poses }).isProtected(inside)).isFalse() // no range on this backend
    }

    @Test
    fun `the target query never offers protected players but keeps dummies`() {
        val registry = TrainingDummyRegistry()
        val dummy = TrainingDummy(spot).also(registry::register)
        val body = newId().also { registry.bind(dummy, it) }
        val poses = FakePlayerPoseLookup()
        val inside = newId().also { poses.place(it, plan.spawn, world = lobby) }
        val outside = newId().also { poses.place(it, Vec3(0.5, 65.0, 6.5), world = lobby) }
        val protection = TrainingRangeProtection({ range }, registry, { poses })
        val query = ProtectionAwareNearbyTargetQuery(FakeNearbyTargetQuery(listOf(inside, outside, body)), protection)

        assertThat(query.findInRadius(plan.spawn, 50.0, lobby)).containsExactlyInAnyOrder(outside, body)
        assertThat(query.findNearby(plan.spawn, 50.0, lobby).map(NearbyTarget::id)).containsExactlyInAnyOrder(outside, body)
    }

    // ---- maintenance loop --------------------------------------------------------------------

    private class FakeShells : ITrainingDummyShells {
        val bodies = linkedMapOf<PlayerId, Vec3>()
        val spotOf = HashMap<PlayerId, String>()
        val labels = HashMap<PlayerId, List<String>>()
        var worldReady = true
        var spawnCalls = 0

        override fun spawn(world: WorldId, spot: TrainingDummySpot, at: Vec3): PlayerId? {
            spawnCalls++
            if (!worldReady) return null
            val id = PlayerId(UUID.randomUUID())
            bodies[id] = at
            spotOf[id] = spot.id
            return id
        }

        override fun isAlive(id: PlayerId) = id in bodies
        override fun positionOf(id: PlayerId) = bodies[id]
        override fun moveTo(id: PlayerId, at: Vec3) {
            bodies[id] = at
        }

        override fun setLabel(id: PlayerId, lines: List<String>) {
            labels[id] = lines
        }

        override fun remove(id: PlayerId) {
            bodies.remove(id)
        }

        override fun removeAll() = bodies.clear()
    }

    private class FakeDisplays : IDisplayEntityGateway {
        val spawned = mutableListOf<Pair<Vec3, List<String>>>()
        val removed = mutableListOf<IDisplayEntityGateway.DisplayHandle>()
        override fun spawn(world: WorldId, at: Vec3, spec: DisplaySpec, renderedLines: List<String>): IDisplayEntityGateway.DisplayHandle {
            spawned += at to renderedLines
            return IDisplayEntityGateway.DisplayHandle(UUID.randomUUID())
        }

        override fun update(handle: IDisplayEntityGateway.DisplayHandle, transform: com.astarworks.astera.core.domain.effect.DisplayTransform, interpolationTicks: Int) = Unit
        override fun setBlock(handle: IDisplayEntityGateway.DisplayHandle, blockKey: String) = Unit
        override fun setText(handle: IDisplayEntityGateway.DisplayHandle, renderedLines: List<String>) = Unit
        override fun teleport(handle: IDisplayEntityGateway.DisplayHandle, at: Vec3, yaw: Float, pitch: Float, teleportTicks: Int) = Unit
        override fun remove(handle: IDisplayEntityGateway.DisplayHandle) {
            removed += handle
        }
    }

    private class Rig(range: LobbyTrainingRange) {
        val scheduler = FakeScheduler()
        val registry = TrainingDummyRegistry()
        val shells = FakeShells()
        val displays = FakeDisplays()
        val service = TrainingRangeService({ range }, registry, shells, displays, scheduler, FakeMessageRenderer())
    }

    @Test
    fun `the loop spawns one body per planned dummy and keeps them in place`() {
        val rig = Rig(range)
        rig.service.tick(now = 2)
        assertThat(rig.shells.bodies).hasSize(plan.dummies.size)
        assertThat(rig.registry.all().all { it.bodyId != null }).isTrue()

        // knockback shoves a range dummy: the next pass puts it back
        val dummy = rig.registry.forSpot("range-10")!!
        rig.shells.bodies[dummy.bodyId!!] = Vec3(1000.0, 65.0, 0.0)
        rig.service.tick(now = 4)
        assertThat(rig.shells.bodies[dummy.bodyId!!]).isEqualTo(dummy.spot.position)

        // a body that vanished (chunk unload, /kill) is replaced
        val old = dummy.bodyId!!
        rig.shells.bodies.remove(old)
        rig.service.tick(now = 6)
        assertThat(dummy.bodyId).isNotNull().isNotEqualTo(old)
        assertThat(rig.registry.isDummy(old)).isFalse()
        assertThat(rig.shells.bodies).hasSize(plan.dummies.size)
    }

    @Test
    fun `a world that is not loaded yet only costs one retry per second`() {
        val rig = Rig(range)
        rig.shells.worldReady = false
        (2L..18L step 2).forEach { rig.service.tick(it) }
        assertThat(rig.shells.spawnCalls).isEqualTo(1)
        rig.shells.worldReady = true
        rig.service.tick(now = 22)
        assertThat(rig.shells.bodies).hasSize(plan.dummies.size)
    }

    @Test
    fun `the moving target follows its track`() {
        val rig = Rig(range)
        rig.service.tick(now = 2)
        val mover = rig.registry.forSpot("mover-1")!!
        val track = mover.spot.track!!
        rig.service.tick(now = 100)
        val (x, z) = track.positionAt(100)
        assertThat(rig.shells.bodies[mover.bodyId!!]).isEqualTo(Vec3(x, mover.spot.y, z))
        assertThat(x).isGreaterThan(track.fromX)
    }

    @Test
    fun `a hit shows a short-lived damage number and updates the HP and DPS tag, then the dummy refills`() {
        val rig = Rig(range)
        rig.service.tick(now = 2)
        val dummy = rig.registry.forSpot("range-10")!!
        val body = dummy.bodyId!!
        assertThat(rig.shells.labels[body]!!.last()).contains("astera.training.dummy.stats").contains("hp=500")

        rig.scheduler.advanceTicks(10)
        dummy.hit(50.0, rig.scheduler.currentTick())
        rig.service.onHit(dummy, resolved(50.0), source)
        assertThat(rig.displays.spawned.single().second.single()).contains("-50.0")
        assertThat(rig.shells.labels[body]!!.last()).contains("hp=450").contains("dps=10.0")
        // the number is removed again after its lifetime
        assertThat(rig.displays.removed).isEmpty()
        rig.scheduler.advanceTicks(TrainingRangeService.NUMBER_LIFETIME_TICKS)
        assertThat(rig.displays.removed).hasSize(1)

        // idle for 3 s -> refilled, tag back to full with no DPS
        rig.service.tick(rig.scheduler.currentTick() + TrainingDummy.REGEN_IDLE_TICKS)
        assertThat(dummy.hp).isEqualTo(500.0)
        assertThat(rig.shells.labels[body]!!.last()).contains("hp=500").contains("dps=0.0")
    }

    @Test
    fun `a critical hit is shown differently`() {
        val rig = Rig(range)
        rig.service.tick(now = 2)
        val dummy = rig.registry.forSpot("range-5")!!
        rig.service.onHit(dummy, resolved(40.0, crit = true), source)
        assertThat(rig.displays.spawned.single().second.single()).contains("gold").contains("40.0")
    }

    @Test
    fun `a backend without the range does nothing`() {
        val scheduler = FakeScheduler()
        val shells = FakeShells()
        val service = TrainingRangeService({ null }, TrainingDummyRegistry(), shells, null, scheduler, null)
        service.tick(now = 2)
        assertThat(shells.spawnCalls).isEqualTo(0)
    }
}
