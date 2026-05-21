package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.application.port.outbound.anticheat.IMovementValidator
import com.astarworks.astera.core.application.service.packet.InMemoryPacketInterceptor
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.anticheat.MovementRejection
import com.astarworks.astera.core.domain.anticheat.MovementSuspicion
import com.astarworks.astera.core.domain.anticheat.MovementValidation
import com.astarworks.astera.core.domain.event.AnticheatActionTaken
import com.astarworks.astera.core.domain.event.AnticheatFlagRaised
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.packet.PacketDecision
import com.astarworks.astera.core.domain.packet.PacketEnvelope
import com.astarworks.astera.core.domain.packet.PacketKind
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.anticheat.FakeExternalAnticheatBridge
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MovementValidatorPacketBridgeTest {

    private val player = PlayerId(UUID.randomUUID())

    private fun permissionService(grantsBypass: Boolean): IPermissionService =
        object : IPermissionService {
            override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> = Result.success(Unit)
            override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> = Result.success(Unit)
            override fun check(playerId: PlayerId, permission: Permission): Boolean =
                grantsBypass && permission == AnticheatOrchestrator.BYPASS_PERMISSION
            override fun rolesOf(playerId: PlayerId): Set<RoleId> = emptySet()
            override fun definitionOf(roleId: RoleId): RoleDefinition? = null
            override fun allDefinitions(): Collection<RoleDefinition> = emptyList()
        }

    private fun bridge(
        verdicts: List<MovementValidation>,
        bypass: Boolean = false,
        bus: FakeDomainEventBus = FakeDomainEventBus(),
    ): Pair<MovementValidatorPacketBridge, FakeDomainEventBus> {
        val queue = ArrayDeque(verdicts)
        val validator = object : IMovementValidator {
            override fun validate(playerId: PlayerId, from: Vec3, to: Vec3, deltaTicks: Int): MovementValidation =
                if (queue.isNotEmpty()) queue.removeFirst() else MovementValidation.Ok
        }
        val orch = AnticheatOrchestrator(
            externalBridge = FakeExternalAnticheatBridge(),
            permissionService = permissionService(bypass),
            eventBus = bus,
        )
        return MovementValidatorPacketBridge(validator, orch, tickProvider = { 42L }) to bus
    }

    private fun envAt(x: Double, y: Double, z: Double, tick: Long = 0L): PacketEnvelope =
        PacketEnvelope(
            kind = PacketKind.MovementServerbound,
            playerId = player,
            payloadView = mapOf("x" to x, "y" to y, "z" to z),
            atTick = tick,
        )

    @Test
    fun `first packet for a player seeds the cache and never validates`() {
        val (bridge, bus) = bridge(verdicts = listOf(MovementValidation.Reject(MovementRejection.NoClip)))

        val decision = bridge.invoke(envAt(0.0, 64.0, 0.0))

        assertThat(decision).isEqualTo(PacketDecision.Pass)
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `Ok verdict on second packet returns Pass and emits nothing`() {
        val (bridge, bus) = bridge(verdicts = listOf(MovementValidation.Ok))

        bridge.invoke(envAt(0.0, 64.0, 0.0))
        val decision = bridge.invoke(envAt(0.5, 64.0, 0.0))

        assertThat(decision).isEqualTo(PacketDecision.Pass)
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `Suspicious verdict reports a flag and returns Pass`() {
        val (bridge, bus) = bridge(
            verdicts = listOf(
                MovementValidation.Suspicious(MovementSuspicion.HighSpeed(measured = 99.0, max = 8.0)),
            ),
        )

        bridge.invoke(envAt(0.0, 64.0, 0.0))
        val decision = bridge.invoke(envAt(99.0, 64.0, 0.0))

        assertThat(decision).isEqualTo(PacketDecision.Pass)
        val flags = bus.publishedOfType<AnticheatFlagRaised>()
        assertThat(flags).hasSize(1)
        assertThat(flags[0].atTick).isEqualTo(42L)
    }

    @Test
    fun `Reject verdict returns Cancel, reports a flag, and rolls back the cache`() {
        val (bridge, bus) = bridge(
            verdicts = listOf(
                MovementValidation.Reject(MovementRejection.NoClip),
                MovementValidation.Ok,
            ),
        )

        bridge.invoke(envAt(0.0, 64.0, 0.0))
        val rejectedDecision = bridge.invoke(envAt(50.0, 64.0, 0.0))

        assertThat(rejectedDecision).isEqualTo(PacketDecision.Cancel)
        assertThat(bus.publishedOfType<AnticheatFlagRaised>()).hasSize(1)
        assertThat(bus.publishedOfType<AnticheatActionTaken>()).hasSize(1)

        // The next packet should validate against the ORIGINAL last-known
        // position (0,64,0), not the rejected (50,64,0). The next verdict in
        // the queue is Ok — proves the bridge re-asked the validator after
        // rolling back the cache.
        val followUp = bridge.invoke(envAt(0.5, 64.0, 0.0))
        assertThat(followUp).isEqualTo(PacketDecision.Pass)
    }

    @Test
    fun `bypassed player still gets Pass on Suspicious - no flag emitted`() {
        val (bridge, bus) = bridge(
            verdicts = listOf(
                MovementValidation.Suspicious(MovementSuspicion.HighSpeed(measured = 99.0, max = 8.0)),
            ),
            bypass = true,
        )

        bridge.invoke(envAt(0.0, 64.0, 0.0))
        val decision = bridge.invoke(envAt(99.0, 64.0, 0.0))

        assertThat(decision).isEqualTo(PacketDecision.Pass)
        // Orchestrator.report short-circuits on bypass — no events at all.
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `forget drops the cache so the next packet seeds again`() {
        val (bridge, bus) = bridge(
            verdicts = listOf(MovementValidation.Reject(MovementRejection.NoClip)),
        )

        bridge.invoke(envAt(0.0, 64.0, 0.0))
        bridge.forget(player)

        // No verdict consumed because seed packet must not validate.
        val decision = bridge.invoke(envAt(50.0, 64.0, 0.0))

        assertThat(decision).isEqualTo(PacketDecision.Pass)
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `attach plugs into the interceptor and routes MovementServerbound through`() {
        val interceptor = InMemoryPacketInterceptor()
        val (bridge, _) = bridge(verdicts = listOf(MovementValidation.Reject(MovementRejection.NoClip)))
        val sub = bridge.attach(interceptor)

        assertThat(interceptor.listenerCount(PacketKind.MovementServerbound)).isEqualTo(1)
        assertThat(sub.isActive).isTrue()

        interceptor.dispatch(envAt(0.0, 64.0, 0.0))
        val decision = interceptor.dispatch(envAt(50.0, 64.0, 0.0))

        assertThat(decision).isEqualTo(PacketDecision.Cancel)
    }

    @Test
    fun `non-movement envelope is ignored even if accidentally routed`() {
        val (bridge, bus) = bridge(verdicts = listOf(MovementValidation.Reject(MovementRejection.NoClip)))

        val nonMovement = PacketEnvelope(
            kind = PacketKind.ChatClientbound,
            playerId = player,
            payloadView = mapOf("message" to "hi"),
            atTick = 0L,
        )

        val decision = bridge.invoke(nonMovement)

        assertThat(decision).isEqualTo(PacketDecision.Pass)
        assertThat(bus.published).isEmpty()
    }
}
