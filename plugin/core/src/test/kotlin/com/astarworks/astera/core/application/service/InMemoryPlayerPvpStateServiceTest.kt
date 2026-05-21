package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.event.PvpDamageBlocked
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.pvp.PvpDisableReason
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeSpawnProtectionGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPlayerPvpStateServiceTest {

    private val attacker = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000a01"))
    private val victim = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000a02"))
    private val origin = Vec3(0.0, 64.0, 0.0)

    private lateinit var scheduler: FakeScheduler
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var protection: FakeSpawnProtectionGateway
    private lateinit var service: InMemoryPlayerPvpStateService

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler()
        eventBus = FakeDomainEventBus()
        protection = FakeSpawnProtectionGateway()
        service = InMemoryPlayerPvpStateService(scheduler, eventBus, protection)
    }

    @Test
    fun `default flag is enabled`() {
        assertThat(service.isEnabled(victim)).isTrue
    }

    @Test
    fun `disable then enable round-trip`() {
        service.disable(victim, PvpDisableReason.AdminOverride)
        assertThat(service.isEnabled(victim)).isFalse
        service.enable(victim)
        assertThat(service.isEnabled(victim)).isTrue
    }

    @Test
    fun `resolveCanDamage allows when nothing blocks`() {
        assertThat(service.resolveCanDamage(attacker, victim, origin)).isTrue
        assertThat(eventBus.publishedOfType<PvpDamageBlocked>()).isEmpty()
    }

    @Test
    fun `resolveCanDamage blocks with SpawnProtection when victim is protected`() {
        protection.protectedIds += victim
        assertThat(service.resolveCanDamage(attacker, victim, origin)).isFalse
        val blocked = eventBus.publishedOfType<PvpDamageBlocked>().single()
        assertThat(blocked.reason).isEqualTo(PvpDisableReason.SpawnProtection)
        assertThat(blocked.attackerId).isEqualTo(attacker)
        assertThat(blocked.victimId).isEqualTo(victim)
    }

    @Test
    fun `resolveCanDamage blocks with SafeZone when the position predicate fires`() {
        val s = InMemoryPlayerPvpStateService(
            scheduler, eventBus, protection,
            safeZonePredicate = { it == origin },
        )
        assertThat(s.resolveCanDamage(attacker, victim, origin)).isFalse
        val blocked = eventBus.publishedOfType<PvpDamageBlocked>().single()
        assertThat(blocked.reason).isEqualTo(PvpDisableReason.SafeZone)
    }

    @Test
    fun `resolveCanDamage blocks with stored reason when victim is disabled`() {
        service.disable(victim, PvpDisableReason.NewPlayer)
        assertThat(service.resolveCanDamage(attacker, victim, origin)).isFalse
        assertThat(eventBus.publishedOfType<PvpDamageBlocked>().single().reason)
            .isEqualTo(PvpDisableReason.NewPlayer)
    }

    @Test
    fun `protection takes precedence over disabled-victim reason`() {
        service.disable(victim, PvpDisableReason.NewPlayer)
        protection.protectedIds += victim
        assertThat(service.resolveCanDamage(attacker, victim, origin)).isFalse
        assertThat(eventBus.publishedOfType<PvpDamageBlocked>().single().reason)
            .isEqualTo(PvpDisableReason.SpawnProtection)
    }

    @Test
    fun `attacker-disabled also blocks when victim is enabled`() {
        service.disable(attacker, PvpDisableReason.AdminOverride)
        assertThat(service.resolveCanDamage(attacker, victim, origin)).isFalse
        assertThat(eventBus.publishedOfType<PvpDamageBlocked>().single().reason)
            .isEqualTo(PvpDisableReason.AdminOverride)
    }
}
