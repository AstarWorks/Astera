package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.application.port.outbound.anticheat.IMovementValidator
import com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor
import com.astarworks.astera.core.domain.anticheat.AnticheatSuspicion
import com.astarworks.astera.core.domain.anticheat.MovementValidation
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.packet.InterceptorSubscription
import com.astarworks.astera.core.domain.packet.PacketDecision
import com.astarworks.astera.core.domain.packet.PacketEnvelope
import com.astarworks.astera.core.domain.packet.PacketKind
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5y bridge that promotes Wave 5q's [IMovementValidator] from "called by
 * the BukkitEventAdapter on `PlayerMoveEvent`" to "called per-packet on the
 * inbound `MovementServerbound` packet" (ADR-0090).
 *
 * **Why packet-level?** `PlayerMoveEvent` fires once Paper has already updated
 * the player's logical position, which means a cheating client can race the
 * server to land hits before the move is rejected. Intercepting on the packet
 * pipeline gives the validator the *raw* candidate position before Paper
 * applies it; on [MovementValidation.Reject] the bridge returns
 * [PacketDecision.Cancel] to drop the packet, and Paper's next tick will
 * snap the client back to its last good position.
 *
 * **Last-known-position cache.** The validator needs `from` and `to`. The
 * packet only carries `to`; the bridge tracks the last-known position
 * per-player in [lastKnownPosition], seeded on the first observed packet
 * (no validation runs on the first packet of a session — too little context).
 *
 * **Tick provider** is injected so JUnit tests can drive deterministic
 * `atTick` values; production wiring passes
 * `{ scheduler.currentTick() }` from the Wave 5q AsteraCorePlugin block.
 *
 * **Bypass path.** This bridge delegates the bypass check to the underlying
 * [AnticheatOrchestrator.report] (which is the only side-effect on suspicious
 * verdicts); on a [MovementValidation.Reject] verdict, the bridge issues
 * [PacketDecision.Cancel] regardless of bypass — the bypass flag exists to
 * silence *flags*, not to permit physically impossible moves. Admins testing
 * teleport hacks should `/op` themselves or set
 * `astera.anticheat.bypass.hard`, which Wave 6b's Paper move-listener honours
 * separately.
 */
public class MovementValidatorPacketBridge(
    private val movementValidator: IMovementValidator,
    private val orchestrator: AnticheatOrchestrator,
    private val tickProvider: () -> Long = { 0L },
) : (PacketEnvelope) -> PacketDecision {

    private val lastKnownPosition: MutableMap<PlayerId, Vec3> = HashMap()

    override fun invoke(envelope: PacketEnvelope): PacketDecision {
        if (envelope.kind != PacketKind.MovementServerbound) return PacketDecision.Pass

        val target = extractPosition(envelope.payloadView) ?: return PacketDecision.Pass
        val previous = lastKnownPosition[envelope.playerId]
        lastKnownPosition[envelope.playerId] = target

        if (previous == null) {
            // Seed only — too little context to validate.
            return PacketDecision.Pass
        }

        val verdict = movementValidator.validate(
            playerId = envelope.playerId,
            from = previous,
            to = target,
            deltaTicks = 1,
        )

        return when (verdict) {
            MovementValidation.Ok -> PacketDecision.Pass
            is MovementValidation.Suspicious -> {
                orchestrator.report(
                    playerId = envelope.playerId,
                    suspicion = AnticheatSuspicion.Movement(verdict.reason),
                    atTick = tickProvider(),
                )
                PacketDecision.Pass
            }
            is MovementValidation.Reject -> {
                orchestrator.report(
                    playerId = envelope.playerId,
                    suspicion = AnticheatSuspicion.Movement(
                        com.astarworks.astera.core.domain.anticheat.MovementSuspicion.WallClipping(
                            from = previous,
                            to = target,
                        ),
                    ),
                    atTick = tickProvider(),
                )
                // Roll back the cached last-known to the previous good position
                // so the next packet isn't validated against the cancelled candidate.
                lastKnownPosition[envelope.playerId] = previous
                PacketDecision.Cancel
            }
        }
    }

    /**
     * Wire this bridge into [interceptor] for [PacketKind.MovementServerbound].
     * Caller retains the subscription and cancels it during plugin teardown.
     */
    public fun attach(interceptor: IPacketInterceptor): InterceptorSubscription =
        interceptor.register(PacketKind.MovementServerbound, this)

    /**
     * Clear the cached last-known position for [playerId]. Called on
     * `PlayerLeftServer` so the next session starts with no `from` to
     * validate against.
     */
    public fun forget(playerId: PlayerId) {
        lastKnownPosition.remove(playerId)
    }

    private fun extractPosition(payload: Map<String, Any>): Vec3? {
        val x = (payload["x"] as? Double) ?: return null
        val y = (payload["y"] as? Double) ?: return null
        val z = (payload["z"] as? Double) ?: return null
        return Vec3(x, y, z)
    }

}
