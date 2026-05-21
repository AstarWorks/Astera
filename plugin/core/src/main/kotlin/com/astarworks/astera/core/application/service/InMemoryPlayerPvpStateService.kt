package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerPvpStateService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.domain.event.PvpDamageBlocked
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.pvp.PvpDisableReason
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IPlayerPvpStateService] (Wave 5o, ADR-0081).
 *
 * Holds:
 *  - the per-player on/off flag (default `true`),
 *  - the most-recent disable reason (so re-disabling with the same reason is
 *    a no-op and avoids double-firing events).
 *
 * Resolves `canDamage` by consulting (in order):
 *  1. spawn protection — [protectionGateway] returns `true` for `victim` →
 *     [PvpDisableReason.SpawnProtection]
 *  2. safe-zone — [safeZonePredicate] returns `true` for `position` →
 *     [PvpDisableReason.SafeZone] (the caller wires this to the future
 *     Wave 5n region-permission service; the in-memory default is
 *     "no safe zones")
 *  3. per-player flag — victim has PvP off → [PvpDisableReason.AdminOverride]
 *     (or the stored reason if `disable` was called with one explicitly)
 *
 * Every `false` return publishes a [PvpDamageBlocked] event with the cause.
 */
public class InMemoryPlayerPvpStateService(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val protectionGateway: ISpawnProtectionGateway,
    private val safeZonePredicate: (Vec3) -> Boolean = { false },
) : IPlayerPvpStateService {

    private val disabled: MutableMap<PlayerId, PvpDisableReason> = ConcurrentHashMap()

    override fun isEnabled(playerId: PlayerId): Boolean = !disabled.containsKey(playerId)

    override fun enable(playerId: PlayerId) {
        disabled.remove(playerId)
    }

    override fun disable(playerId: PlayerId, reason: PvpDisableReason) {
        disabled[playerId] = reason
    }

    override fun resolveCanDamage(
        attacker: PlayerId,
        victim: PlayerId,
        position: Vec3,
    ): Boolean {
        val blocker: PvpDisableReason = resolveBlocker(attacker, victim, position) ?: return true
        eventBus.publish(
            PvpDamageBlocked(
                attackerId = attacker,
                victimId = victim,
                reason = blocker,
                atTick = scheduler.currentTick(),
            ),
        )
        return false
    }

    private fun resolveBlocker(
        attacker: PlayerId,
        victim: PlayerId,
        position: Vec3,
    ): PvpDisableReason? {
        if (protectionGateway.isProtected(victim)) return PvpDisableReason.SpawnProtection
        if (safeZonePredicate(position)) return PvpDisableReason.SafeZone
        disabled[victim]?.let { return it }
        disabled[attacker]?.let { return it }
        return null
    }
}
