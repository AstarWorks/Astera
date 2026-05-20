package com.astarworks.astera.core.application.service.permission

import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.permission.IPermissionBackend
import com.astarworks.astera.core.application.port.outbound.permission.RoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.PermissionBackendSwitched
import com.astarworks.astera.core.domain.event.PermissionDenied
import com.astarworks.astera.core.domain.event.RoleGranted
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Unit coverage for Wave 5t (ADR-0086) DefaultPermissionService selector.
 *
 * Verifies: selector picks the first-available backend, falls through when
 * the head backend is unavailable, emits PermissionBackendSwitched on flip,
 * emits PermissionDenied on negative check, emits RoleGranted on successful
 * grant.
 */
class DefaultPermissionServiceTest {

    private class CapturingBroadcaster : IBroadcaster {
        val events = mutableListOf<DomainEvent>()
        override fun publish(event: DomainEvent) {
            events += event
        }
    }

    private class StubBackend(
        private val backendId: String,
        private val available: () -> Boolean = { true },
        private val checkReturns: Boolean = false,
        private val grantOutcome: Result<Unit, RoleError> = Result.success(Unit),
    ) : IPermissionBackend {
        override fun backendId(): String = backendId
        override fun isAvailable(): Boolean = available()
        override fun check(playerId: PlayerId, node: String): Boolean = checkReturns
        override fun rolesOf(playerId: PlayerId): Set<RoleId> = emptySet()
        override fun grant(
            playerId: PlayerId,
            roleId: RoleId,
            duration: Duration?,
            grantedBy: PlayerId?,
        ): Result<Unit, RoleError> = grantOutcome

        override fun revoke(
            playerId: PlayerId,
            roleId: RoleId,
            revokedBy: PlayerId?,
        ): Result<Unit, RoleError> = Result.success(Unit)
    }

    @Test
    fun `selector picks first available backend`() {
        val br = CapturingBroadcaster()
        val luckperms = StubBackend("luckperms", available = { true }, checkReturns = true)
        val minimal = StubBackend("minimal", checkReturns = false)
        val svc = DefaultPermissionService(listOf(luckperms, minimal), br)
        val player = PlayerId(UUID.randomUUID())
        assertThat(svc.check(player, "any.node")).isTrue
    }

    @Test
    fun `selector falls through when head backend unavailable`() {
        val br = CapturingBroadcaster()
        val luckperms = StubBackend("luckperms", available = { false }, checkReturns = true)
        val minimal = StubBackend("minimal", checkReturns = true)
        val svc = DefaultPermissionService(listOf(luckperms, minimal), br)
        val player = PlayerId(UUID.randomUUID())
        assertThat(svc.check(player, "any.node")).isTrue
    }

    @Test
    fun `flipping availability emits PermissionBackendSwitched`() {
        val br = CapturingBroadcaster()
        var luckpermsUp = false
        val luckperms = StubBackend("luckperms", available = { luckpermsUp }, checkReturns = true)
        val minimal = StubBackend("minimal", checkReturns = false)
        val svc = DefaultPermissionService(listOf(luckperms, minimal), br)
        val player = PlayerId(UUID.randomUUID())

        // First call: LuckPerms down, Minimal serves → no flip from initial.
        svc.check(player, "any.node")
        // Flip LuckPerms up; next call should switch and emit.
        luckpermsUp = true
        svc.check(player, "any.node")
        assertThat(br.events.filterIsInstance<PermissionBackendSwitched>())
            .hasSize(1)
            .singleElement()
            .satisfies({
                assertThat(it.oldBackend).isEqualTo("minimal")
                assertThat(it.newBackend).isEqualTo("luckperms")
            })
    }

    @Test
    fun `denied check emits PermissionDenied`() {
        val br = CapturingBroadcaster()
        val minimal = StubBackend("minimal", checkReturns = false)
        val svc = DefaultPermissionService(listOf(minimal), br)
        val player = PlayerId(UUID.randomUUID())
        svc.check(player, "astera.forbidden")
        assertThat(br.events.filterIsInstance<PermissionDenied>())
            .singleElement()
            .satisfies({
                assertThat(it.node).isEqualTo("astera.forbidden")
            })
    }

    @Test
    fun `successful grant emits RoleGranted`() {
        val br = CapturingBroadcaster()
        val minimal = StubBackend("minimal", grantOutcome = Result.success(Unit))
        val svc = DefaultPermissionService(listOf(minimal), br)
        val player = PlayerId(UUID.randomUUID())
        val outcome = svc.grant(player, RoleId("astera:player"))
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat(br.events.filterIsInstance<RoleGranted>()).hasSize(1)
    }

    @Test
    fun `init rejects duplicate backend ids`() {
        val br = CapturingBroadcaster()
        val a = StubBackend("dup")
        val b = StubBackend("dup")
        try {
            DefaultPermissionService(listOf(a, b), br)
            error("expected IAE")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).contains("unique")
        }
    }
}
