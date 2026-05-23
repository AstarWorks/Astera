package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.mob.BossInstance
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.BossDefeated
import com.astarworks.astera.core.domain.event.BossPhaseChanged
import com.astarworks.astera.core.domain.event.BossSpawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobBackendId
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.boss.BossError
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.boss.BossPhase
import com.astarworks.astera.core.domain.mob.boss.BossPhaseTransition
import com.astarworks.astera.core.domain.mob.boss.BossSpec
import com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryBossLifecycleServiceTest {

    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var auditWriter: InMemoryBossKillAuditWriter
    private lateinit var spawner: StubMobSpawner
    private lateinit var service: InMemoryBossLifecycleService

    private val world = WorldId("test-world")
    private val origin = Vec3(0.0, 0.0, 0.0)
    private val bossId = BossId("astera:test-boss")
    private val baseMob = MobTypeId("astera:test-base")
    private val killer = PlayerId(UUID.randomUUID())
    private val party = PartyId.random()

    @BeforeEach
    fun setup() {
        eventBus = FakeDomainEventBus()
        auditWriter = InMemoryBossKillAuditWriter()
        spawner = StubMobSpawner()
        service = InMemoryBossLifecycleService(spawner, eventBus, auditWriter)
    }

    private fun threePhaseSpec(
        raidLock: RaidLockPolicy = RaidLockPolicy.OPEN,
        recordKill: Boolean = true,
    ) = BossSpec(
        id = bossId,
        displayName = LocalizedText(mapOf(LocaleCode.EN to "Test Boss")),
        baseMobType = baseMob,
        phases = listOf(
            BossPhase(0, "boss.test.phase.0"),
            BossPhase(1, "boss.test.phase.1"),
            BossPhase(2, "boss.test.phase.2"),
        ),
        transitions = listOf(
            BossPhaseTransition.HpThreshold(fromPhase = 0, toPhase = 1, hpFraction = 0.66),
            BossPhaseTransition.HpThreshold(fromPhase = 1, toPhase = 2, hpFraction = 0.33),
        ),
        raidLock = raidLock,
        recordKill = recordKill,
    )

    @Test
    fun `spawn unknown boss returns UnknownBoss`() {
        val result = service.spawn(BossId("astera:never-registered"), world, origin)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(BossError.UnknownBoss::class.java)
    }

    @Test
    fun `spawn publishes BossSpawned and tracks instance`() {
        service.registerSpec(threePhaseSpec())
        val handle = (service.spawn(bossId, world, origin) as Result.Success).value
        assertThat(service.activeInstanceCount()).isEqualTo(1)
        assertThat(service.instanceOf(handle.mobId)?.currentPhase).isEqualTo(0)
        val spawned = eventBus.publishedOfType<BossSpawned>()
        assertThat(spawned).hasSize(1)
        assertThat(spawned[0].bossId).isEqualTo(bossId)
    }

    @Test
    fun `onDamage transitions phase when HP threshold crossed`() {
        service.registerSpec(threePhaseSpec())
        val handle = (service.spawn(bossId, world, origin) as Result.Success).value

        // Damage to 0.7 — still in phase 0
        val r1 = service.onDamage(handle.mobId, killer, partyId = null, 0.7, atTick = 1L)
        assertThat((r1 as Result.Success).value.currentPhase).isEqualTo(0)
        assertThat(eventBus.publishedOfType<BossPhaseChanged>()).isEmpty()

        // Damage to 0.5 — crosses 0.66, enters phase 1
        val r2 = service.onDamage(handle.mobId, killer, partyId = null, 0.5, atTick = 2L)
        assertThat((r2 as Result.Success).value.currentPhase).isEqualTo(1)
        val changes = eventBus.publishedOfType<BossPhaseChanged>()
        assertThat(changes).hasSize(1)
        assertThat(changes[0].fromPhase).isEqualTo(0)
        assertThat(changes[0].toPhase).isEqualTo(1)

        // Damage to 0.2 — crosses 0.33, enters phase 2
        val r3 = service.onDamage(handle.mobId, killer, partyId = null, 0.2, atTick = 3L)
        assertThat((r3 as Result.Success).value.currentPhase).isEqualTo(2)
        assertThat(eventBus.publishedOfType<BossPhaseChanged>()).hasSize(2)
    }

    @Test
    fun `PartyLock requires party id`() {
        service.registerSpec(threePhaseSpec(raidLock = RaidLockPolicy.PartyLock(maxPartySize = 4)))
        val handle = (service.spawn(bossId, world, origin) as Result.Success).value

        val result = service.onDamage(handle.mobId, killer, partyId = null, 0.9, atTick = 1L)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(BossError.PartyRequired::class.java)
    }

    @Test
    fun `PartyLock claims first hitting party`() {
        service.registerSpec(threePhaseSpec(raidLock = RaidLockPolicy.PartyLock(maxPartySize = 4)))
        val handle = (service.spawn(bossId, world, origin) as Result.Success).value

        val r = service.onDamage(handle.mobId, killer, partyId = party, 0.9, atTick = 1L)
        val updated = (r as Result.Success).value
        assertThat(updated.isClaimed).isTrue
        assertThat(updated.owningParty).isEqualTo(party)
    }

    @Test
    fun `PartyLock rejects damage from non-owning party`() {
        service.registerSpec(threePhaseSpec(raidLock = RaidLockPolicy.PartyLock(maxPartySize = 4)))
        val handle = (service.spawn(bossId, world, origin) as Result.Success).value
        service.onDamage(handle.mobId, killer, partyId = party, 0.9, atTick = 1L)

        val intruderParty = PartyId.random()
        val intruder = PlayerId(UUID.randomUUID())
        val rejected = service.onDamage(handle.mobId, intruder, partyId = intruderParty, 0.8, atTick = 2L)
        val err = (rejected as Result.Failure).error
        assertThat(err).isInstanceOf(BossError.RaidLocked::class.java)
        assertThat((err as BossError.RaidLocked).partyId).isEqualTo(party)
    }

    @Test
    fun `onDeath publishes BossDefeated and writes audit row`() {
        service.registerSpec(threePhaseSpec())
        val handle = (service.spawn(bossId, world, origin) as Result.Success).value
        service.onDamage(handle.mobId, killer, partyId = null, 0.2, atTick = 5L)

        val result = service.onDeath(handle.mobId, killerId = killer, atTick = 10L)
        val finalInstance = (result as Result.Success).value
        assertThat(finalInstance.hpFraction).isEqualTo(0.0)
        assertThat(service.activeInstanceCount()).isZero

        val defeated = eventBus.publishedOfType<BossDefeated>()
        assertThat(defeated).hasSize(1)
        assertThat(defeated[0].killerId).isEqualTo(killer)
        assertThat(defeated[0].fightDurationTicks).isEqualTo(10L)
        assertThat(auditWriter.count()).isEqualTo(1L)
    }

    @Test
    fun `onDeath skips audit when recordKill is false`() {
        service.registerSpec(threePhaseSpec(recordKill = false))
        val handle = (service.spawn(bossId, world, origin) as Result.Success).value

        service.onDeath(handle.mobId, killerId = killer, atTick = 5L)
        assertThat(auditWriter.count()).isZero
        // Event still publishes
        assertThat(eventBus.publishedOfType<BossDefeated>()).hasSize(1)
    }

    @Test
    fun `onDamage on unknown mob returns UnknownMob`() {
        val r = service.onDamage(
            com.astarworks.astera.core.domain.mob.runtime.MobId.random(),
            killer,
            partyId = null,
            0.5,
            atTick = 1L,
        )
        assertThat((r as Result.Failure).error)
            .isInstanceOf(BossError.UnknownMob::class.java)
    }

    @Test
    fun `audit writer idempotent on duplicate mobId`() {
        service.registerSpec(threePhaseSpec())
        val handle = (service.spawn(bossId, world, origin) as Result.Success).value
        val event = BossDefeated(
            mobId = handle.mobId,
            bossId = bossId,
            killerId = killer,
            partyId = null,
            fightDurationTicks = 5L,
            finalPhase = 0,
            atTick = 5L,
        )
        assertThat(auditWriter.record(event)).isTrue
        assertThat(auditWriter.record(event)).isFalse
        assertThat(auditWriter.count()).isEqualTo(1L)
    }

    /**
     * Minimal stub spawner: mints an EntityHandle with a random UUID for
     * every spawn call, ignores despawn, reports MINIMAL as the active
     * backend. Sufficient for the lifecycle service tests which only need
     * the underlying spawn to succeed.
     */
    private class StubMobSpawner : IMobSpawner {
        override fun spawn(world: WorldId, position: Vec3, mobTypeId: MobTypeId): Result<EntityHandle, MobError> =
            Result.success(EntityHandle(UUID.randomUUID(), MobBackendId.MINIMAL.value))

        override fun despawn(handle: EntityHandle): Result<Unit, MobError> = Result.success(Unit)

        override fun activeBackendId(): MobBackendId = MobBackendId.MINIMAL
    }
}
