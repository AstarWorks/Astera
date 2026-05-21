package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.domain.anticheat.AnticheatAction
import com.astarworks.astera.core.domain.anticheat.AnticheatSuspicion
import com.astarworks.astera.core.domain.anticheat.MovementSuspicion
import com.astarworks.astera.core.domain.event.AnticheatActionTaken
import com.astarworks.astera.core.domain.event.AnticheatFlagRaised
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.anticheat.FakeExternalAnticheatBridge
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AnticheatOrchestratorTest {

    private val player = PlayerId(UUID.randomUUID())

    @Test
    fun `low-severity Movement flag emits Notify and AnticheatActionTaken`() {
        val bus = FakeDomainEventBus()
        val bridge = FakeExternalAnticheatBridge()
        val perms = InMemoryPermissionService()
        val orchestrator = AnticheatOrchestrator(bridge, perms, bus)
        val action = orchestrator.report(
            player,
            AnticheatSuspicion.Movement(MovementSuspicion.HighSpeed(1.5, 1.0)),
            atTick = 42L,
        )
        assertThat(action).isEqualTo(AnticheatAction.Notify)
        assertThat(bus.publishedOfType<AnticheatFlagRaised>()).hasSize(1)
        assertThat(bus.publishedOfType<AnticheatActionTaken>())
            .singleElement()
            .extracting(AnticheatActionTaken::action)
            .isEqualTo(AnticheatAction.Notify)
        assertThat(bridge.reports).singleElement().satisfies({ pair ->
            assertThat(pair.first).isEqualTo(player)
        })
    }

    @Test
    fun `external flag level 8 escalates to Ban`() {
        val bus = FakeDomainEventBus()
        val bridge = FakeExternalAnticheatBridge().apply { flagLevels[player] = 8 }
        val perms = InMemoryPermissionService()
        val orchestrator = AnticheatOrchestrator(bridge, perms, bus)
        val action = orchestrator.report(
            player,
            AnticheatSuspicion.Movement(MovementSuspicion.HighSpeed(2.0, 1.0)),
            atTick = 0L,
        )
        assertThat(action).isEqualTo(AnticheatAction.Ban)
    }

    @Test
    fun `admin role with wildcard permission bypasses anti-cheat`() {
        val bus = FakeDomainEventBus()
        val bridge = FakeExternalAnticheatBridge()
        val perms = InMemoryPermissionService()
        // DefaultRoles.ADMIN holds Permission("*") which subsumes
        // astera.anticheat.bypass via the wildcard check in
        // InMemoryPermissionService.check().
        perms.assign(player, DefaultRoles.ADMIN.id)
        val orchestrator = AnticheatOrchestrator(bridge, perms, bus)
        val action = orchestrator.report(
            player,
            AnticheatSuspicion.DamageRate(dps = 50.0, cap = 8.0),
            atTick = 0L,
        )
        assertThat(action).isNull()
        assertThat(bus.published).isEmpty()
        assertThat(bridge.reports).isEmpty()
    }

    // Sanity probe: keep Permission reference live so the
    // permission package classpath stays in scope for this test module.
    @Test
    fun `permission domain types stay reachable`() {
        assertThat(Permission("astera.anticheat.test").id).isEqualTo("astera.anticheat.test")
    }
}
